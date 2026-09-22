package app.hypochlorite.player

import app.hypochlorite.netease.ListenParticipant
import app.hypochlorite.netease.ListenPlayback
import app.hypochlorite.netease.ListenPlaylistVersion
import app.hypochlorite.netease.ListenRoomKind
import app.hypochlorite.netease.NeteaseClient
import app.hypochlorite.netease.RoomInfo
import app.hypochlorite.netease.RoomUser
import app.hypochlorite.netease.SessionStore
import app.hypochlorite.netease.Song
import app.hypochlorite.netease.incrementListenVersion
import app.hypochlorite.netease.isListenClosed
import app.hypochlorite.netease.listenCreatedRoomId
import app.hypochlorite.netease.listenPlayback
import app.hypochlorite.netease.listenQueueEditCommand
import app.hypochlorite.netease.listenQueueNeedsSync
import app.hypochlorite.netease.listenRoomStatus
import app.hypochlorite.netease.mergeListenVersions
import app.hypochlorite.netease.parseListenInvite
import app.hypochlorite.netease.requireListenSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** 拉状态和播放列表的间隔。比心跳密，这样切歌不用等十秒。 */
private const val REFRESH_MS = 3_000L

/** 心跳只用来报「我还在」，不拿它当播放时钟。 */
private const val HEARTBEAT_MS = 10_000L

/** 进度差过了这个值才 seek。再小两边会互相拽。 */
private const val SEEK_TOLERANCE_MS = 4_000L

/** 列表同步落地之后再发播放指令，不然指令会打在旧列表上。 */
private const val PLAYLIST_SETTLE_MS = 400L

/** 拖动排序会连着改好几格。停一下再报，避免每一格都打一次列表同步。 */
private const val QUEUE_EDIT_MS = 600L

/** 房间链路。界面用它区分「接通中 / 已同步 / 正在重连」，不把断线画成已经退出。 */
enum class ListenLink {
    Idle,
    Connecting,
    Live,
    Reconnecting,
}

/**
 * 一起听状态。
 *
 * [room] 在的时候人还在房间里，哪怕 [link] 是 [ListenLink.Reconnecting]。
 * [connected] 跟 [room] 一起亮：底栏图标表示「在房间里」，不是「这一拍网络刚好通」。
 */
data class ListenTogetherState(
    val room: RoomInfo? = null,
    val connected: Boolean = false,
    val hosting: Boolean = false,
    val syncing: Boolean = false,
    val link: ListenLink = ListenLink.Idle,
    val error: String? = null,
    val toast: String? = null,
    val roomQueue: List<Song> = emptyList(),
)

private data class PlayKey(val trackId: String?, val playing: Boolean)

/**
 * 一起听同步。
 *
 * 服务端不报权威时钟，只转发播放列表和播放指令。这边的做法和官方客户端一致：
 *
 * - 建房分双人 / 多人。加入必须带邀请人和房间号，先 accept 再 check。
 * - 每 3 秒拉 status 和 playlist。播放对齐看 playlist 里的 playCommand，按 clientSeq 去重。
 * - 本地切歌、播放、暂停从播放器状态里报出去；拖动进度单独报 SEEK。
 * - 只改队列（加歌、删歌、调顺序）不切歌，停一停再补一次列表同步，指令保持播放或暂停。
 * - 自己刚套用的远端状态记在 [pending]，避免回声再报一遍。
 * - 488，或者连续两次 status 说不在房间，就退出。
 */
class ListenTogether(
    private val client: NeteaseClient,
    private val player: HypochloritePlayer,
    private val session: SessionStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ListenTogetherState())
    val state: StateFlow<ListenTogetherState> = _state

    private val gate = Mutex()
    private var actionJob: Job? = null
    private var loopJob: Job? = null
    private var queueReportJob: Job? = null
    private var generation = 0

    private var sequence = 1L
    private var versions: List<ListenPlaylistVersion> = emptyList()
    private var playback: ListenPlayback? = null
    private var lastReportedIds: List<Long> = emptyList()
    private var lastAppliedSeq = -1L
    private var pending: PlayKey? = null
    private var lastStable: PlayKey? = null
    private var lastHeartbeatAt = 0L
    private var pendingInvite: String? = null

    val roomId: String? get() = _state.value.room?.roomId

    init {
        observePlayback()
        observeQueue()
    }

    fun peekPending(): Boolean = !pendingInvite.isNullOrBlank()

    fun onLoggedIn() {
        val pending = pendingInvite ?: return
        join(pending)
    }

    /** 退出登录前把房间结束掉。调用方要在清 cookie 之前 await。 */
    suspend fun endForLogout() {
        val id = _state.value.room?.roomId
        if (id != null) {
            withContext(Dispatchers.IO) { runCatching { client.ltEnd(id) } }
        }
        clearLocal()
        pendingInvite = null
    }

    fun createRoom(kind: ListenRoomKind = ListenRoomKind.Duo) {
        if (!ready()) return
        actionJob?.cancel()
        actionJob = scope.launch {
            _state.update { it.copy(syncing = true, error = null) }
            try {
                val response = when (kind) {
                    ListenRoomKind.Duo -> withContext(Dispatchers.IO) { client.ltCreateRoom() }
                    ListenRoomKind.Multi -> {
                        val snap = player.state.value
                        val songId = snap.current?.id?.toLongOrNull()
                            ?: error("先放一首歌，再开多人房")
                        withContext(Dispatchers.IO) {
                            client.ltCreateMultiRoom(songId, snap.positionMs, upcomingIds(snap.current?.id))
                        }
                    }
                }
                if (isListenClosed(response)) error("一起听已经结束了")
                requireListenSuccess(response)
                val roomId = listenCreatedRoomId(response) ?: error("房间信息没拿到，稍后再试")
                val check = withContext(Dispatchers.IO) { client.ltRoomCheck(roomId) }
                requireListenSuccess(check)
                val uid = userId() ?: error("得先登录网易云才能一起听")
                invalidateLoop()
                enter(roomId, uid, hosting = true, listenRoomStatus(check)?.participants.orEmpty())
                gate.withLock {
                    resetCounters()
                    if (player.state.value.current != null) report("GOTO", forceQueue = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e, "房间创建失败，确认登录后再试")
            } finally {
                _state.update { it.copy(syncing = false) }
                if (_state.value.room != null && loopJob?.isActive != true) startLoop()
            }
        }
    }

    fun join(invitation: String) {
        val trimmed = invitation.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(error = "把邀请链接贴进来") }
            return
        }
        if (!session.canUseListenTogether()) {
            pendingInvite = trimmed
            _state.update { it.copy(error = "得先登录网易云才能一起听", toast = "还没登录网易云") }
            return
        }
        val invite = parseListenInvite(trimmed)
        if (invite == null) {
            _state.update { it.copy(error = "没有从邀请里找到房间，换一条链接再试") }
            return
        }
        pendingInvite = null
        actionJob?.cancel()
        actionJob = scope.launch {
            _state.update { it.copy(syncing = true, error = null) }
            try {
                val accepted = withContext(Dispatchers.IO) { client.ltAccept(invite.roomId, invite.inviterId) }
                if (isListenClosed(accepted)) error("一起听已经结束了")
                requireListenSuccess(accepted)
                val check = withContext(Dispatchers.IO) { client.ltRoomCheck(invite.roomId) }
                requireListenSuccess(check)
                invalidateLoop()
                enter(invite.roomId, invite.inviterId, hosting = false, listenRoomStatus(check)?.participants.orEmpty())
                gate.withLock { resetCounters() }
                startLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e, "加入失败，邀请可能已经失效")
            } finally {
                _state.update { it.copy(syncing = false) }
            }
        }
    }

    fun leaveRoom() {
        val room = _state.value.room ?: return
        val hosting = _state.value.hosting
        actionJob?.cancel()
        actionJob = scope.launch {
            _state.update { it.copy(syncing = true, error = null) }
            try {
                val response = withContext(Dispatchers.IO) { client.ltEnd(room.roomId) }
                if (!isListenClosed(response)) requireListenSuccess(response)
                clearLocal(if (hosting) "房间结束了" else "已离开房间", room.roomId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e, "暂时没法退出，稍后再试")
            } finally {
                _state.update { it.copy(syncing = false) }
            }
        }
    }

    /**
     * 冷启动回查。服务端说还在房间里才续上；网络失败就留着本地记录，下次再试。
     */
    fun restore() {
        if (_state.value.room != null || actionJob?.isActive == true) return
        if (!session.canUseListenTogether()) return
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { client.ltStatus() }
                if (isListenClosed(response)) {
                    session.saveListenRoom(null, false)
                    return@launch
                }
                requireListenSuccess(response)
                val status = listenRoomStatus(response)
                val roomId = status?.roomId
                if (status == null || !status.inRoom || roomId == null) {
                    session.saveListenRoom(null, false)
                    return@launch
                }
                if (_state.value.room != null) return@launch
                val uid = userId() ?: return@launch
                val hosting = session.isListenHosting() && session.getListenRoomId() == roomId
                val inviter = session.getListenInviterId()?.toLongOrNull() ?: uid
                enter(roomId, inviter, hosting, status.participants)
                gate.withLock { resetCounters() }
                startLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun clearToast() {
        if (_state.value.toast != null) _state.update { it.copy(toast = null) }
    }

    fun notify(message: String) {
        if (message.isNotEmpty()) _state.update { it.copy(toast = message) }
    }

    /** 扫码打不开这类留在房间页上的失败。和 toast 不一样，它得停着让人看见。 */
    fun note(message: String) {
        if (message.isNotEmpty()) _state.update { it.copy(error = message) }
    }

    /** 拖动进度。切歌和播放暂停由播放器状态自己报，拖动不会改那两个字段。 */
    fun broadcastSeek(positionMs: Long) {
        if (_state.value.room == null) return
        scope.launch {
            try {
                gate.withLock { report("SEEK", forceQueue = false, progressOverride = positionMs.coerceAtLeast(0L)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** 把一首歌补进房间队列，不切过去。 */
    fun addSongToRoomQueue(song: Song) {
        if (_state.value.room == null) {
            _state.update { it.copy(toast = "先进一个房间，才有地方放歌") }
            return
        }
        if (player.state.value.queue.any { it.id == song.id }) {
            _state.update { it.copy(toast = song.name + " 已经在房间里了") }
            return
        }
        player.addToQueue(song)
        scope.launch {
            try {
                val playing = player.state.value.playing
                gate.withLock { report(if (playing) "PLAY" else "PAUSE", forceQueue = true) }
                _state.update { it.copy(toast = song.name + " 已经放进房间了") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(toast = e.message?.takeIf { it.isNotBlank() } ?: "没能放进去，网络不太好") }
            }
        }
    }

    private fun ready(): Boolean {
        if (session.canUseListenTogether() && userId() != null) return true
        _state.update { it.copy(error = "得先登录网易云才能一起听", toast = "还没登录网易云") }
        return false
    }

    private fun userId(): Long? = session.getProfile()?.userId?.toLongOrNull()?.takeIf { it > 0L }

    private fun upcomingIds(currentId: String?): List<Long> {
        if (currentId == null) return emptyList()
        val queue = player.state.value.queue
        val index = queue.indexOfFirst { it.id == currentId }
        if (index < 0) return emptyList()
        return queue.drop(index + 1).mapNotNull { it.id.toLongOrNull() }.distinct()
    }

    private fun enter(roomId: String, inviterId: Long, hosting: Boolean, members: List<ListenParticipant>) {
        queueReportJob?.cancel()
        queueReportJob = null
        _state.update {
            it.copy(
                room = snapshot(roomId, inviterId, hosting, members),
                connected = true,
                hosting = hosting,
                link = ListenLink.Connecting,
                error = null,
                roomQueue = player.state.value.queue,
            )
        }
        session.saveListenRoom(roomId, hosting, inviterId.toString())
    }

    private fun snapshot(
        roomId: String,
        inviterId: Long,
        hosting: Boolean,
        members: List<ListenParticipant>,
    ): RoomInfo {
        val self = session.getProfile()?.userId
        val owner = if (hosting) self else inviterId.toString()
        return RoomInfo(
            roomId = roomId,
            ownerId = owner,
            inviterId = inviterId.toString(),
            users = members.map { person ->
                RoomUser(
                    userId = person.userId.toString(),
                    nickname = person.nickname,
                    avatarUrl = person.avatarUrl.orEmpty(),
                )
            },
        )
    }

    private fun fail(error: Exception, fallback: String) {
        _state.update { it.copy(error = error.message?.takeIf { text -> text.isNotBlank() } ?: fallback) }
    }

    /** 停掉正在跑的轮询，并让已经发出去的那一轮回来时作废。 */
    private fun invalidateLoop() {
        generation += 1
        loopJob?.cancel()
        loopJob = null
    }

    private fun resetCounters() {
        queueReportJob?.cancel()
        queueReportJob = null
        sequence = 1L
        versions = emptyList()
        playback = null
        lastReportedIds = emptyList()
        lastAppliedSeq = -1L
        pending = null
        lastHeartbeatAt = 0L
    }

    private fun clearLocal(toast: String? = null, roomId: String? = null) {
        if (roomId != null && _state.value.room?.roomId != roomId) return
        invalidateLoop()
        resetCounters()
        _state.value = ListenTogetherState(toast = toast)
        session.saveListenRoom(null, false)
    }

    private fun startLoop() {
        val gen = generation
        loopJob?.cancel()
        loopJob = scope.launch {
            var missing = 0
            while (isActive && generation == gen && _state.value.room != null) {
                try {
                    val closed = gate.withLock { poll(gen) { missed ->
                        if (missed) missing += 1 else missing = 0
                        missing
                    } }
                    if (closed || generation != gen) break
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (generation != gen) break
                    _state.update { state ->
                        if (state.room == null) state else state.copy(link = ListenLink.Reconnecting, connected = true)
                    }
                }
                delay(REFRESH_MS)
            }
        }
    }

    /**
     * 一次轮询。[onMiss] 收到「服务端说不在房间」并返回累计次数。
     * 返回 true 表示房间已经结束，循环该停了。
     */
    private suspend fun poll(gen: Int, onMiss: (Boolean) -> Int): Boolean {
        val room = _state.value.room ?: return true
        if (generation != gen) return true
        val statusResponse = withContext(Dispatchers.IO) { client.ltStatus() }
        if (generation != gen) return true
        if (isListenClosed(statusResponse)) {
            clearLocal("一起听已经结束了", room.roomId)
            return true
        }
        requireListenSuccess(statusResponse)
        val status = listenRoomStatus(statusResponse)
        if (status?.inRoom == false) {
            if (onMiss(true) >= 2) {
                clearLocal("一起听已经结束了", room.roomId)
                return true
            }
            return false
        }
        onMiss(false)

        val playlistResponse = withContext(Dispatchers.IO) { client.ltPlaylist(room.roomId) }
        if (generation != gen) return true
        if (isListenClosed(playlistResponse)) {
            clearLocal("一起听已经结束了", room.roomId)
            return true
        }
        requireListenSuccess(playlistResponse)
        val remote = listenPlayback(playlistResponse)
        if (remote != null) {
            playback = remote
            versions = mergeListenVersions(versions, remote.versions)
            sequence = maxOf(sequence, remote.clientSequence + 1L)
            applyRemote(remote, gen)
        }
        if (generation != gen) return true

        val members = status?.participants?.takeIf { it.isNotEmpty() }
        _state.update { state ->
            val current = state.room ?: return@update state
            if (current.roomId != room.roomId) return@update state
            val nextUsers = members?.map { person ->
                RoomUser(person.userId.toString(), person.nickname, person.avatarUrl.orEmpty())
            }
            if (nextUsers == null) {
                if (state.connected && state.link == ListenLink.Live && state.error == null) return@update state
                return@update state.copy(connected = true, link = ListenLink.Live, error = null)
            }
            if (nextUsers == current.users &&
                state.connected &&
                state.link == ListenLink.Live &&
                state.error == null
            ) {
                return@update state
            }
            state.copy(
                room = current.copy(users = nextUsers),
                connected = true,
                link = ListenLink.Live,
                error = null,
            )
        }

        val now = System.currentTimeMillis()
        val snap = player.state.value
        val songId = snap.current?.id?.toLongOrNull()
        if (songId != null && now - lastHeartbeatAt >= HEARTBEAT_MS) {
            val beat = withContext(Dispatchers.IO) {
                client.ltHeartbeat(
                    room.roomId,
                    songId,
                    if (snap.playing) "PLAY" else "PAUSE",
                    snap.positionMs,
                )
            }
            if (generation != gen) return true
            if (isListenClosed(beat)) {
                clearLocal("一起听已经结束了", room.roomId)
                return true
            }
            requireListenSuccess(beat)
            lastHeartbeatAt = now
        }
        return false
    }

    private suspend fun applyRemote(remote: ListenPlayback, gen: Int) {
        if (generation != gen) return
        val targetId = remote.targetSongId.takeIf { it > 0L } ?: return
        val target = targetId.toString()
        val ids = remote.trackIds.ifEmpty { listOf(targetId) }.let { list ->
            if (targetId in list) list else list + targetId
        }
        val idStrings = ids.map { it.toString() }
        val local = player.state.value
        val sameList = local.queue.map { it.id } == idStrings
        if (remote.clientSequence <= lastAppliedSeq && sameList) return

        val first = lastAppliedSeq < 0L
        if (remote.clientSequence > lastAppliedSeq) lastAppliedSeq = remote.clientSequence
        val shouldPlay = remote.playStatus.equals("PLAY", ignoreCase = true)

        if (local.current?.id == target && !sameList) {
            val songs = loadSongs(ids)
            if (generation != gen) return
            if (songs.any { it.id == target }) {
                // 先记下即将落地的队列，播放器一更新，队列监听就不会把它再报回去。
                lastReportedIds = songs.mapNotNull { it.id.toLongOrNull() }
                player.replaceQueue(songs, keepCurrent = true)
            }
        }

        val snap = player.state.value
        val aligned = snap.current?.id == target &&
            snap.playing == shouldPlay &&
            abs(snap.positionMs - remote.progressMillis) < SEEK_TOLERANCE_MS &&
            snap.queue.map { it.id } == idStrings
        if (aligned) {
            lastReportedIds = ids
            return
        }

        if (snap.current?.id != target) {
            pending = PlayKey(target, shouldPlay)
            val songs = loadSongs(ids)
            if (generation != gen) return
            if (songs.none { it.id == target }) {
                pending = null
                return
            }
            lastReportedIds = songs.mapNotNull { it.id.toLongOrNull() }
            player.playQueue(songs, target, remote.progressMillis, shouldPlay)
            return
        }

        val drift = abs(snap.positionMs - remote.progressMillis)
        val seekable = remote.commandType.uppercase() in setOf("GOTO", "SEEK") || first
        if (drift >= SEEK_TOLERANCE_MS && seekable) player.seek(remote.progressMillis)
        when {
            shouldPlay && !snap.playing -> {
                pending = PlayKey(target, true)
                player.resume()
            }
            !shouldPlay && snap.playing -> {
                pending = PlayKey(target, false)
                player.pause()
            }
            else -> if (pending?.trackId == target && pending?.playing == snap.playing) pending = null
        }
    }

    private suspend fun loadSongs(ids: List<Long>): List<Song> {
        val known = player.state.value.queue.associateBy { it.id }.toMutableMap()
        val missing = ids.map { it.toString() }.filter { it !in known }
        missing.chunked(200).forEach { chunk ->
            val fetched = withContext(Dispatchers.IO) {
                runCatching { client.getSongDetails(chunk) }.getOrDefault(emptyList())
            }
            fetched.forEach { known[it.id] = it }
        }
        return ids.mapNotNull { known[it.toString()] }
    }

    private suspend fun report(commandType: String, forceQueue: Boolean, progressOverride: Long? = null) {
        val room = _state.value.room ?: return
        val uid = userId() ?: return
        val snap = player.state.value
        val trackId = snap.current?.id?.toLongOrNull() ?: return
        val queueIds = snap.queue.mapNotNull { it.id.toLongOrNull() }.distinct().ifEmpty { listOf(trackId) }
        if (forceQueue || queueIds != lastReportedIds) {
            val nextVersions = incrementListenVersion(versions, uid)
            val synced = withContext(Dispatchers.IO) {
                client.ltSyncPlaylist(room.roomId, nextVersions, playback?.playMode ?: "ORDER_LOOP", queueIds)
            }
            if (isListenClosed(synced)) {
                clearLocal("一起听已经结束了", room.roomId)
                return
            }
            requireListenSuccess(synced)
            versions = nextVersions
            lastReportedIds = queueIds
            delay(PLAYLIST_SETTLE_MS)
        }
        if (_state.value.room?.roomId != room.roomId) return
        val former = playback?.targetSongId?.takeIf { it > 0L } ?: -1L
        val seq = sequence.coerceAtLeast(1L)
        sequence = seq + 1L
        val response = withContext(Dispatchers.IO) {
            client.ltPlayCommand(
                roomId = room.roomId,
                commandType = commandType,
                progressMillis = progressOverride ?: player.state.value.positionMs,
                playStatus = if (player.state.value.playing) "PLAY" else "PAUSE",
                formerSongId = former,
                targetSongId = trackId,
                clientSeq = seq,
            )
        }
        if (isListenClosed(response)) {
            clearLocal("一起听已经结束了", room.roomId)
            return
        }
        requireListenSuccess(response)
        lastAppliedSeq = maxOf(lastAppliedSeq, seq)
    }

    private fun observePlayback() {
        scope.launch {
            player.state
                .map { it.loading to PlayKey(it.current?.id, it.playing) }
                .distinctUntilChanged()
                .collect { (loading, key) ->
                    if (loading) return@collect
                    val previous = lastStable
                    lastStable = key
                    val expected = pending
                    if (expected != null) {
                        if (expected == key) {
                            pending = null
                            return@collect
                        }
                        if (expected.trackId == key.trackId && expected.playing && !key.playing) return@collect
                    }
                    if (_state.value.room == null || key.trackId == null) return@collect
                    val command = when {
                        previous?.trackId != key.trackId -> "GOTO"
                        previous.playing != key.playing -> if (key.playing) "PLAY" else "PAUSE"
                        else -> return@collect
                    }
                    launch {
                        try {
                            gate.withLock { report(command, forceQueue = command == "GOTO") }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                }
        }
    }

    private fun observeQueue() {
        scope.launch {
            player.state
                .map { it.queue }
                .distinctUntilChanged { old, new -> old === new }
                .collect { queue ->
                    val ids = queueIds(queue)
                    _state.update { state ->
                        if (state.room == null || state.roomQueue === queue) state
                        else state.copy(roomQueue = queue)
                    }
                    queueReportJob?.cancel()
                    if (!listenQueueNeedsSync(_state.value.room != null, ids, lastReportedIds)) return@collect
                    queueReportJob = launch {
                        delay(QUEUE_EDIT_MS)
                        try {
                            gate.withLock {
                                val now = player.state.value
                                val nowIds = queueIds(now.queue)
                                if (!listenQueueNeedsSync(_state.value.room != null, nowIds, lastReportedIds)) {
                                    return@withLock
                                }
                                report(listenQueueEditCommand(now.playing), forceQueue = true)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            _state.update { state ->
                                if (state.room == null) state
                                else state.copy(error = e.message?.takeIf { it.isNotBlank() } ?: "队列没能同步过去")
                            }
                        }
                    }
                }
        }
    }

    private fun queueIds(queue: List<Song>): List<Long> =
        queue.mapNotNull { it.id.toLongOrNull() }.distinct()
}
