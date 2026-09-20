package app.hypochlorite.player

import app.hypochlorite.netease.NeteaseClient
import app.hypochlorite.netease.RoomInfo
import app.hypochlorite.netease.RoomPlayback
import app.hypochlorite.netease.RoomUser
import app.hypochlorite.netease.SessionStore
import app.hypochlorite.netease.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** 心跳间隔。太密会被限流，太疏对方会把你判成掉线。 */
private const val HEARTBEAT_INTERVAL_MS = 5_000L

/** 位置偏离超过这个值才值得强制对齐；太小会导致两边反复互相拉扯。 */
private const val RESYNC_THRESHOLD_MS = 800L

/** 本地触发的操作在多长时间内不理会远端指令，避免自己的回声把自己拽回去。 */
private const val ECHO_GUARD_MS = 900L

/**
 * 一次播放同步事件，从房间流里出来、被投喂给 [ListenTogetherState.lastEvent]。
 *
 * [songId] 为空表示只改了播放状态（暂停 / 继续），[progressMs] 是**发送端**的位置。
 * 接收端要自己加上网络往返的补偿 —— 这个值算不准，宁可稍微提前一点。
 */
data class RemoteCommand(
    val songId: String?,
    val playing: Boolean,
    val progressMs: Long,
    val seq: Long,
    val fromSelf: Boolean,
)

/**
 * 一起听状态。
 *
 * [connected] 是「本机认为自己在某个房间里」，[room] 是最近一次拿到的房间快照。
 * 两者分开：网络断了但房间还在时，UI 应该显示房间信息 + 一个断连提示，
 * 而不是把整个房间清空。
 */
data class ListenTogetherState(
    val room: RoomInfo? = null,
    val connected: Boolean = false,
    val hosting: Boolean = false,
    val syncing: Boolean = false,
    val error: String? = null,
    val lastEvent: RemoteCommand? = null,
    /**
     * 房间队列（歌曲对象）。**这就是唯一的队列** —— 在房间里时本机队列由它派生，
     * 不存在「本机队列 + 房间副本」两套。
     */
    val roomQueue: List<Song> = emptyList(),
    /**
     * 一次性操作反馈。和 [error] 分开：error 是「房间里持续存在的问题」，
     * 会一直显示在房间页；toast 是某个动作的即时结果，飘一下就该消失。
     */
    val toast: String? = null,
)

/**
 * 一起听同步引擎 —— 把网易云的房间协议接到 [HypochloritePlayer] 上。
 *
 * 设计要点：
 *
 * 1. **房间协议是「指令流」而不是「共享时钟」。** 服务端不做权威计时，它只是转发
 *    play/command/report。所以同步精度完全取决于发送端把 progress 报得多准，
 *    以及接收端把「网络往返」补偿得多好。这里统一用 [HypochloritePlayer.playAt] 的
 *    `initialSeekMs` 参数在加载时直接落到目标位置，不做「先播再 seek」——
 *    后者会先响一小段原位置的声音，听感上就是「对不齐」。
 *
 * 2. **房主（owner）才是权威。** 只有房主会主动发 PLAY / PAUSE / GOTO，
 *    成员只发心跳 + seek 自己的本地进度。成员硬拽进度会被房主的心跳立刻拉回去，
 *    形成两端互相拉扯的抖动。
 *
 * 3. **防回声靠 [lastLocalActionAt]。** 房主自己按下播放时，服务端会把这条指令
 *    回灌一份。如果不设防，房主会收到自己的指令再执行一次（表现为进度条跳一下、
 *    甚至二次起播），所以本地操作后 [ECHO_GUARD_MS] 内忽略 `fromSelf` 的事件。
 */
class ListenTogether(
    private val client: NeteaseClient,
    private val player: HypochloritePlayer,
    private val session: SessionStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ListenTogetherState())
    val state: StateFlow<ListenTogetherState> = _state

    /** 心跳 + 拉取远端状态的循环 */
    private var loopJob: Job? = null

    /** 远端指令的应用任务（串行化，避免两条指令同时改播放器） */
    private var applyJob: Job? = null

    /** 本地主动发指令的时间戳，用于回声防护 */
    private var lastLocalActionAt = 0L

    /** 指令序号，服务端用它排序 */
    private var clientSeq = 1L

    /** 播放列表版本号，sync/list/command 用它做冲突消解 */
    private var listVersion = 1L

    /** 最近一次向服务端上报过的 (songId, playing)，没变化就不重复发 */
    private var lastReported: Pair<String, Boolean>? = null

    /** 上一次已知的房间队列 id 列表，用来检测「房间队列变了」。 */
    private var _lastQueueIds: List<String> = emptyList()

    val roomId: String? get() = _state.value.room?.roomId

    val isHosting: Boolean get() = _state.value.hosting

    // ------------------------------------------------------------------ 生命周期

    /**
     * 创建房间并成为房主。
     *
     * 创建后会立刻把当前队列同步进房间 —— 否则成员进来是一片空白，
     * 得等房主手动「加载歌单」才有内容，第一次用会以为功能坏了。
     */
    fun createRoom() {
        if (!session.canUseListenTogether()) {
            _state.update { it.copy(error = "得先登录网易云才能一起听", toast = "还没登录网易云") }
            return
        }
        scope.launch {
            _state.update { it.copy(syncing = true, error = null) }
            // 失败原因是服务端给的（满员 / 风控 / 登录过期……），拿回来原样告诉用户，
            // 不要再统一压成「创建房间失败」—— 那样用户除了重试无事可做。
            val result: Result<RoomInfo> = withContext(Dispatchers.IO) {
                runCatching { client.ltCreateRoom() }.getOrElse { Result.failure(it) }
            }
            val room: RoomInfo? = result.getOrNull()
            if (room == null) {
                val why = result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: "没能建上房间，稍后再试"
                _state.update { it.copy(syncing = false, error = why) }
                return@launch
            }
            val uid = session.getProfile()?.userId
            _state.update {
                it.copy(
                    room = room.copy(ownerId = room.ownerId ?: uid),
                    connected = true,
                    hosting = true,
                    syncing = false,
                    error = null,
                )
            }
            session.saveListenRoom(room.roomId, true)
            // 把当前队列变成房间的初始队列（房主先选好歌再建房是很自然的手势）
            syncQueue()
            _lastQueueIds = player.state.value.queue.map { it.id }
            startLoop()
        }
    }

    /**
     * 加入一个房间。
     *
     * 先用 room/check 验证房间存在（顺便拿到成员列表），再拉房间播放列表。
     * 只写 prefs 不校验的话，输错一位数字也会「加入成功」，然后一直空转。
     */
    fun joinRoom(roomId: String) {
        val id = roomId.trim().substringAfter("roomId=").substringBefore('&').trim()
        if (id.isEmpty()) {
            _state.update { it.copy(error = "房间号是空的，把号码填上") }
            return
        }
        if (!id.all { it.isDigit() }) {
            _state.update { it.copy(error = "房间号应该是一串数字，检查一下") }
            return
        }
        if (!session.canUseListenTogether()) {
            _state.update { it.copy(error = "得先登录网易云才能一起听", toast = "还没登录网易云") }
            return
        }
        scope.launch {
            _state.update { it.copy(syncing = true, error = null) }
            val result: Result<RoomInfo> = withContext(Dispatchers.IO) {
                runCatching { client.ltRoomCheck(id) }.getOrElse { Result.failure(it) }
            }
            val room: RoomInfo? = result.getOrNull()
            if (room == null) {
                val why = result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: "没进得去，稍后再试"
                _state.update { it.copy(syncing = false, error = why) }
                return@launch
            }
            val uid = session.getProfile()?.userId
            val hosting = room.ownerId != null && room.ownerId == uid
            _state.update {
                it.copy(room = room, connected = true, hosting = hosting, syncing = false, error = null)
            }
            session.saveListenRoom(room.roomId, hosting)
            pullRoomPlaylist()
            startLoop()
        }
    }

    /**
     * 退出房间。
     *
     * 房主退出会把房间解散（网易云的行为），所以这里不区分角色，一律调 end/v2。
     * 本地状态无论如何都要清掉 —— 就算请求失败（比如网断了），
     * 用户按了「退出」就该退出，不能让 UI 卡在房间里。
     */
    fun leaveRoom() {
        val id = roomId
        loopJob?.cancel()
        loopJob = null
        applyJob?.cancel()
        applyJob = null
        lastReported = null
        _state.value = ListenTogetherState()
        session.saveListenRoom(null, false)
        if (id == null) return
        scope.launch(Dispatchers.IO) { runCatching { client.ltEndRoom(id) } }
    }

    /**
     * 冷启动后续上之前的房间。
     *
     * 不直接用 prefs 里的 roomId 就当在房间里，而是拿 ltStatus 回查 ——
     * app 被杀掉这段时间里房间可能已经散了，直接用旧 id 会出现「显示在房间里但什么都收不到」。
     */
    fun restore() = scope.launch {
        val saved = session.getListenRoomId() ?: return@launch
        if (!session.canUseListenTogether()) return@launch
        val result: Result<RoomInfo?> = withContext(Dispatchers.IO) {
            runCatching { client.ltStatus() }.getOrElse { Result.failure(it) }
        }
        // 回查失败（网络抖动）就先当没这回事，保留 prefs 里的记录，下次启动再试；
        // 只有服务端明确说「你不在任何房间里」才清掉，避免把用户踢出房间。
        val room: RoomInfo? = result.getOrElse { return@launch }
        if (room == null) {
            session.saveListenRoom(null, false)
            return@launch
        }
        val uid = session.getProfile()?.userId
        val hosting = room.ownerId != null && room.ownerId == uid
        _state.update { it.copy(room = room, connected = true, hosting = hosting) }
        session.saveListenRoom(room.roomId, hosting)
        pullRoomPlaylist()
        startLoop()
    }

    /** 飘完了就让 UI 把它收掉。 */
    fun clearToast() {
        if (_state.value.toast != null) _state.update { it.copy(toast = null) }
    }

    /** 给任意动作一条即时反馈（比如「已复制」）。 */
    fun notify(message: String) {
        if (message.isNotEmpty()) _state.update { it.copy(toast = message) }
    }

    // ------------------------------------------------------------------ 房间同步

    /**
     * 有本地操作需要上报给房间时调这里（ViewModel 在 playSong / playAll / next / prev
     * / toggle / seek 之后都会喊一声）。
     *
     * 语义是「我现在是这个状态，房间跟着我来」，不是「我推一份副本给你」。
     * 队列的差异由 [syncQueue] 处理，这里只管当前播放位置。
     */
    fun notifyLocalState() {
        val s = _state.value
        if (!s.connected || s.room == null) return
        // 队列本身变了（比如刚播放了一个新歌单）就先同步队列，再报播放位置
        syncQueue()
    }

    /**
     * 把**当前队列**同步给房间。
     *
     * 在「房间队列 = 唯一队列」的模型下，调用方（房主播放某个歌单、任何人加歌）
     * 都只能经由这个方法改房间队列。协议是整体替换语义，正好对应。
     */
    /**
     * 把**当前队列**同步给房间。
     *
     * 在「房间队列 = 唯一队列」的模型下，这本该是唯一的写入口。但有个重要区分：
     *
     * - **房主**「播放全部」= 换整个房间的歌单，整体替换是对的。
     * - **成员**「播放全部」如果也整体替换，会把房间队列换成他自己刚点的那张歌单，
     *   房主和其他人的队列就被抹了 —— 成员的本意是「我想听这张」，不是「你们都听这张」。
     *   所以成员走**合并**：保留房间已有的歌，把他的加进去。
     */
    fun syncQueue() {
        val room = _state.value.room ?: return
        if (!_state.value.connected) return
        val uid = session.getProfile()?.userId ?: return
        val mine = player.state.value.queue.map { it.id }
        if (mine.isEmpty()) return

        lastLocalActionAt = System.currentTimeMillis()
        scope.launch {
            val base: List<String> = if (_state.value.hosting) {
                emptyList()   // 房主：整体替换
            } else {
                withContext(Dispatchers.IO) {
                    runCatching { client.ltRoomPlaylist(room.roomId) }.getOrDefault(emptyList())
                }
            }
            val ids = if (base.isEmpty()) mine else LinkedHashSet<String>().apply {
                addAll(base)
                addAll(mine)
            }.toList()

            listVersion += 1
            val version = listVersion
            _lastQueueIds = ids
            lastLocalActionAt = System.currentTimeMillis()
            runCatching { client.ltSyncPlaylist(room.roomId, uid, version, ids) }
        }
    }

    /**
     * 确保某首歌在房间队列里（不在才加）。
     *
     * 用在「点播一首不在房间队列里的歌」的场景。**必须在切歌之前调用** ——
     * 它内部会 `addToQueue` 把歌补进本机队列，切歌时才找得到索引；
     * 反过来先切歌再补，会出现「歌在放但队列里没有」的中间态。
     */
    fun addSongToRoomQueueIfMissing(song: Song) {
        val room = _state.value.room ?: return
        if (room.roomId.isEmpty()) return
        if (player.state.value.queue.none { it.id == song.id }) {
            addSongToRoomQueue(song)
        }
    }

    /**
     * 把单首歌加进房间队列（**不切歌**）。
     *
     * 长按的语义 = 点歌台加歌。歌曲不在房间队列就追加到末尾，然后同步队列。
     * 想立即听就走 [requestPlay]，那是另一条路。
     */
    fun addSongToRoomQueue(song: Song) {
        val room = _state.value.room
        if (room == null) {
            _state.update { it.copy(toast = "先进一个房间，才有地方放歌") }
            return
        }
        val uid = session.getProfile()?.userId ?: return
        scope.launch {
            // 以房间现有列表为准合并，避免用本机队列覆盖掉别人的歌
            val base: List<String> = withContext(Dispatchers.IO) {
                runCatching { client.ltRoomPlaylist(room.roomId) }.getOrDefault(emptyList())
            }
            val merged = LinkedHashSet<String>()
            merged.addAll(base)
            merged.add(song.id)
            val ids = merged.toList()

            // 本地也补上这首歌，否则本机队列和房间不一致，后续索引会错位
            if (player.state.value.queue.none { it.id == song.id }) {
                player.addToQueue(song)
            }

            listVersion += 1
            val version = listVersion
            _lastQueueIds = ids
            lastLocalActionAt = System.currentTimeMillis()
            val ok = withContext(Dispatchers.IO) {
                runCatching { client.ltSyncPlaylist(room.roomId, uid, version, ids) }.getOrDefault(false)
            }
            _state.update {
                it.copy(
                    toast = if (ok) song.name + " 已经放进房间了" else "没能放进去，网络不太好",
                )
            }
        }
    }

    /**
     * 拉取房间队列并**完全替换**本机队列。
     *
     * 这是「房间队列 = 唯一队列」的核心动作：进房、重连、以及检测到房间队列变了
     * 都由它把本地拉回正轨。本地不再维护独立队列。
     */
    fun pullRoomPlaylist() {
        val room = _state.value.room ?: return
        scope.launch {
            _state.update { it.copy(syncing = true) }
            val ids: List<String> = withContext(Dispatchers.IO) {
                runCatching { client.ltRoomPlaylist(room.roomId) }.getOrDefault(emptyList())
            }
            // 拉失败（网络抖动 / 房间刚散）时**不要**动 _lastQueueIds ——
            // 设成空会让下一轮心跳把「我们根本不知道房间现在是什么」误当成「房间变了」。
            if (ids.isEmpty()) {
                _state.update { it.copy(syncing = false) }
                return@launch
            }
            val songs: List<Song> = withContext(Dispatchers.IO) {
                runCatching { client.getSongDetails(ids) }.getOrDefault(emptyList())
            }
            // 按房间给的顺序还原（getSongDetails 不保证顺序）
            val byId = songs.associateBy { it.id }
            val ordered = ids.mapNotNull { byId[it] }
            if (ordered.isEmpty()) {
                _state.update { it.copy(syncing = false) }
                return@launch
            }
            _state.update { it.copy(syncing = false, roomQueue = ordered) }
            // 保持当前正在放的那首歌不被打断：只换队列，位置由房间的指令流负责
            player.replaceQueue(ordered, keepCurrent = true)
            _lastQueueIds = ordered.map { it.id }
        }
    }

    // ------------------------------------------------------------------ 本地操作 → 上报

    /** 播放 / 暂停。房间是共享的，谁按的都广播。 */
    fun broadcastToggle(playing: Boolean, positionMs: Long) {
        publishCommand(
            commandType = if (playing) "PLAY" else "PAUSE",
            songId = player.state.value.current?.id,
            playing = playing,
            positionMs = positionMs,
        )
    }

    /**
     * 切歌 / 选歌。
     *
     * GOTO 的语义是「跳到目标歌曲的 0 秒」—— 不带进度，因为换歌本来就要从头放。
     */
    fun broadcastGoto(songId: String) {
        publishCommand(commandType = "GOTO", songId = songId, playing = true, positionMs = 0L)
    }

    /** 进度拖动。用 seek 指令，房间里谁都能发 —— 拉进度条本来就是个「大家一起跳」的动作。 */
    fun broadcastSeek(positionMs: Long) {
        publishCommand(
            commandType = "seek",
            songId = player.state.value.current?.id,
            playing = player.state.value.playing,
            positionMs = positionMs,
        )
    }

    /**
     * 下一首 / 上一首。
     *
     * 在「房间队列是唯一队列」的模型下，next **不能**本地各自算 ——
     * 那会两边算出不同的下一首。统一走房间指令：谁按了就把目标歌广播出去，
     * 另一端收到 GOTO 后跟着切。随机播放模式下也一样，因为目标歌是**由发起者算好**的。
     */
    fun broadcastNext() {
        val q = player.state.value.queue
        if (q.isEmpty()) return
        val i = (player.state.value.index + 1) % q.size
        publishCommand("GOTO", q[i].id, playing = true, positionMs = 0L)
    }

    fun broadcastPrev() {
        val q = player.state.value.queue
        if (q.isEmpty()) return
        val i = (player.state.value.index - 1 + q.size) % q.size
        publishCommand("GOTO", q[i].id, playing = true, positionMs = 0L)
    }

    /**
     * 实际发送指令。三件事必须一起做：
     * 1. 打回声标记（[lastLocalActionAt]），否则服务端回灌时会把本地操作执行第二遍；
     * 2. 递增 clientSeq（服务端据此排序，乱序的旧指令会被丢弃）；
     * 3. 把这些值记进 [lastReported]，让心跳不会紧接着再报一遍同样的状态。
     *
     * **成员也发**。房间是共享点歌台，谁都能切歌 —— 服务端按 clientSeq 排序，
     * 两端同时操作时最后一条生效。曾经的「只有房主能广播」是过度保守：
     * 服务端会把房间的 songId 更新掉，另一端的 `applyRemoteState` 本来就会跟过来。
     */
    private fun publishCommand(commandType: String, songId: String?, playing: Boolean, positionMs: Long) {
        val room = _state.value.room ?: return
        if (!_state.value.connected) return
        val target = songId ?: return

        lastLocalActionAt = System.currentTimeMillis()
        clientSeq += 1
        val seq = clientSeq
        lastReported = target to playing
        // 记下这次操作，等心跳看到房间的 songId 变成它、且自己不是发起者时，
        // 才知道「是谁切过来的」。否则另一端换歌是静默的，用户只会觉得莫名其妙。
        lastBroadcast = PendingAction(target, System.currentTimeMillis())

        val former = player.state.value.current?.id ?: "-1"
        scope.launch(Dispatchers.IO) {
            runCatching {
                client.ltPlayCommand(
                    roomId = room.roomId,
                    commandType = commandType,
                    progressMs = positionMs.coerceAtLeast(0L),
                    playStatus = if (playing) "PLAY" else "PAUSE",
                    formerSongId = former,
                    targetSongId = target,
                    clientSeq = seq,
                )
            }
        }
    }

    /** 一次本地广播的短暂记忆，用来判断房间的变化是不是「别人干的」。 */
    private data class PendingAction(val songId: String, val at: Long)

    /** 上次本地广播 + 时间。超过 [ECHO_GUARD_MS] × 4 就认为跟这次变化无关。 */
    private var lastBroadcast: PendingAction? = null

    /** 已经提示过的远端切歌，避免同一次变化在后续心跳里重复提示。 */
    private var announcedSongId: String? = null

    /**
     * 远端换歌时提示一下是谁切的。
     *
     * 只在「我自己没在 [ECHO_GUARD_MS] 内发过这条指令」时才提示 ——
     * 否则自己点的歌会被算成别人切的。
     */
    private fun announceRemoteSong(remote: RoomInfo, localSongId: String?) {
        val rid = remote.songId ?: return
        if (rid == localSongId) return
        if (announcedSongId == rid) return

        val mine = lastBroadcast
        val selfCaused = mine != null &&
            mine.songId == rid &&
            System.currentTimeMillis() - mine.at < ECHO_GUARD_MS * 4
        announcedSongId = rid
        if (selfCaused) return

        val singer = remote.users.firstOrNull { u ->
            u.userId != session.getProfile()?.userId && u.userId == remote.ownerId
        }
        val name = when {
            singer != null -> displayName(singer)
            else -> "房间里有人"
        }
        scope.launch {
            val song = withContext(Dispatchers.IO) {
                runCatching { client.getSongDetail(rid) }.getOrNull()
            }
            val title = song?.name ?: "换了一首"
            _state.update { it.copy(toast = name + " 换了首 " + title) }
        }
    }

    // ------------------------------------------------------------------ 轮询循环

    /**
     * 心跳循环。
     *
     * 网易云没有给「一起听」提供推送通道（协议里只有 heartbeat 是单向轮询），
     * 所以这里的做法是：**心跳的响应本身就是状态快照**。每 5 秒发一次心跳，
     * 顺带解析响应里的 songId / playStatus / progress —— 谁能收到谁的进度，
     * 取决于服务端把谁当作当前「主持者」。
     *
     * 为了让两端能真正对齐，循环里还做一次「偏差修正」：拿房间报告的进度
     * 跟本机位置比，差超过 [RESYNC_THRESHOLD_MS] 就直接 seek 过去。
     * 这个修正只在房主侧做，成员侧靠指令流对齐，否则两边同时修正会互相打架。
     */
    private fun startLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive) {
                val room = _state.value.room
                if (room == null) break
                val snap = player.state.value
                val songId = snap.current?.id.orEmpty()
                val playing = snap.playing
                val pos = snap.positionMs

                val remote: RoomInfo? = withContext(Dispatchers.IO) {
                    runCatching {
                        client.ltHeartbeat(
                            roomId = room.roomId,
                            songId = songId,
                            playStatus = if (playing) "PLAY" else "PAUSE",
                            progressMs = pos.coerceAtLeast(0L),
                        )
                    }.getOrNull()
                }

                if (remote == null) {
                    // 心跳失败：可能是房间散了（房主退出 / 超时）。标记断连但不立刻清房间，
                    // 给一次重试机会 —— 移动网络下偶发失败很常见。
                    _state.update { it.copy(connected = false) }
                } else {
                    val prevLocalSong = player.state.value.current?.id
                    _state.update { st ->
                        val merged = st.room?.copy(
                            users = if (remote.users.isEmpty()) st.room.users else remote.users,
                            songId = remote.songId ?: st.room.songId,
                            playStatus = remote.playStatus ?: st.room.playStatus,
                            progressMs = remote.progressMs,
                        ) ?: remote
                        st.copy(room = merged, connected = true, error = null)
                    }
                    // 队列是共享的：别人加了歌，这边也得跟上，否则 next/随机 会走岔
                    checkQueueDrift(room.roomId)
                    // 先提示「谁切了歌」，再执行切换 —— 顺序反了的话 toast 会在切歌动画之后才冒出来
                    announceRemoteSong(remote, prevLocalSong)
                    applyRemoteState(remote)
                }

                // 房主定期刷新成员列表（谁进来了、谁走了）
                if (_state.value.hosting) {
                    val fresh: RoomInfo? = withContext(Dispatchers.IO) {
                        runCatching { client.ltRoomCheck(room.roomId).getOrNull() }.getOrNull()
                    }
                    if (fresh != null && fresh.users.isNotEmpty()) {
                        _state.update { it.copy(room = fresh.copy(ownerId = it.room?.ownerId)) }
                    }
                }

                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    /**
     * 房间队列和本地队列不一致就拉回来。
     *
     * 比 id 列表而不是比数量 —— 顺序也会影响 next / 随机的结果。
     *
     * **判断基准是 [_lastQueueIds] 而不是本机 queue。** 本机 queue 里可能有歌还没被
     * 同步进房间（本地乐观更新的中间态），拿它当基准会把「房间没变」误判成
     * 「房间变了」或反过来漏掉真正的变化。 `_lastQueueIds` 代表的是
     * 「上一次确认过的房间队列长什么样」。
     */
    private suspend fun checkQueueDrift(roomId: String) {
        if (System.currentTimeMillis() - lastLocalActionAt < ECHO_GUARD_MS * 2) return
        val remoteIds: List<String> = withContext(Dispatchers.IO) {
            runCatching { client.ltRoomPlaylist(roomId) }.getOrDefault(emptyList())
        }
        if (remoteIds.isEmpty()) return
        if (remoteIds == _lastQueueIds) return
        _lastQueueIds = remoteIds
        pullRoomPlaylist()
    }

    /**
     * 用房间报告的状态修正本地播放。
     *
     * 只在「本地和远端都停了」的情况下才真的动手 —— 两个人同时正常听着的时候
     * 每 5 秒 seek 一次会听出明显的顿挫。真正需要修的是这几种情况：
     * - 远端换了歌，本地还在放旧的；
     * - 远端在放、本地停了（或反过来）；
     * - 进度差得太多（超过 [RESYNC_THRESHOLD_MS]）。
     */
    private fun applyRemoteState(remote: RoomInfo) {
        if (System.currentTimeMillis() - lastLocalActionAt < ECHO_GUARD_MS) return
        val snap = player.state.value
        val localSongId = snap.current?.id
        val remoteSongId = remote.songId

        // 远端在放另一首歌 → 换过去
        if (remoteSongId != null && remoteSongId != localSongId) {
            val at = snap.queue.indexOfFirst { it.id == remoteSongId }
            if (at >= 0) {
                player.playAt(at, initialSeekMs = remote.progressMs.coerceAtLeast(0L), autoPlay = true)
            } else {
                // 队列里没有这首。对方直接播了一首不在房间队列里的歌（比如从搜索结果点的），
                // 他的 `addSongToRoomQueueIfMissing` 可能还没同步完，或者干脆没走到。
                //
                // **不要在这里 pullRoomPlaylist()** —— 拉回来的是房间队列，而房间队列里
                // 本来就没有这首歌，于是本机队列依旧没有它，下一轮心跳又走这里，死循环。
                //
                // 正确做法：把那首歌**补进房间队列**（连同本地的队列一起），
                // 这样两端都前进，而不是原地打转。
                adoptRemoteSong(remoteSongId, remote.progressMs)
            }
            return
        }

        if (remoteSongId == null || remoteSongId != localSongId) return

        val remotePlaying = remote.playStatus?.contains("PLAY", ignoreCase = true) == true
        if (remotePlaying != snap.playing) {
            if (remotePlaying) player.resume() else player.pause()
            return
        }

        // 两边都在放：只修大偏差，避免抖动
        if (remotePlaying && remote.progressMs > 0) {
            val drift = abs(snap.positionMs - remote.progressMs)
            if (drift > RESYNC_THRESHOLD_MS) {
                player.seek(remote.progressMs)
            }
        }
    }

    // ------------------------------------------------------------------ 供 UI 使用的便捷查询

    /**
     * 把远端正在放、但队列里没有的歌补进来（本地 + 房间同时补），然后切过去。
     *
     * 这是「对方点了一首不在房间队列里的歌」的唯一前进路径 —— 补完两端都有这首歌，
     * 下一轮心跳就是一致的；如果只是 pull，房间队列还是缺这首，会无限循环。
     *
     * [adopting] 挡住心跳间隔内的重复触发（补一首歌要一次详情请求，不该每 5 秒发一次）。
     */
    private fun adoptRemoteSong(songId: String, progressMs: Long) {
        if (!adopting.add(songId)) return
        scope.launch {
            val song: Song? = withContext(Dispatchers.IO) {
                runCatching { client.getSongDetail(songId) }.getOrNull()
            }
            if (song == null) {
                adopting.remove(songId)
                return@launch
            }
            // 本地先补进队列（这样 playAt 找得到索引）
            player.addToQueue(song)
            val snap = player.state.value
            val at = snap.queue.indexOfFirst { it.id == songId }
            if (at >= 0) {
                player.playAt(at, initialSeekMs = progressMs.coerceAtLeast(0L), autoPlay = true)
            }
            // 再把「本机队列 = 现在的正确队列」同步回房间，让房间也补上这首歌
            syncQueue()
        }
    }

    /** 正在补的歌，防止心跳每 5 秒重复拉同一首。 */
    private val adopting = mutableSetOf<String>()

    /** 按 userId 找出成员昵称，找不到就回落到 userId —— UI 上宁可显示一串数字也别显示空白 */
    fun displayName(user: RoomUser): String =
        user.nickname.ifEmpty { "用户 ${user.userId.takeLast(6)}" }

    /**
     * 房间播放列表补全（成员侧）。
     *
     * 心跳只带 songId，不带歌曲元信息，所以远端切到一首本地队列里没有的歌时，
     * 得单独把它的详情拉回来插进队列，否则找不到索引就只能卡住不动。
     */
    fun ensureSongInQueue(songId: String, onReady: (Int) -> Unit) {
        val snap = player.state.value
        val existing = snap.queue.indexOfFirst { it.id == songId }
        if (existing >= 0) {
            onReady(existing)
            return
        }
        scope.launch {
            val song = withContext(Dispatchers.IO) {
                runCatching { client.getSongDetail(songId) }.getOrNull()
            } ?: return@launch
            player.appendAndPlay(song)
            onReady(player.state.value.index)
        }
    }
}
