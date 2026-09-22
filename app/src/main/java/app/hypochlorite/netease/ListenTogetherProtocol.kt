package app.hypochlorite.netease

import org.json.JSONArray
import org.json.JSONObject

/** 官方一起听邀请里的两个标识。房间号是不透明字符串，不是纯数字。 */
data class ListenInvite(
    val roomId: String,
    val inviterId: Long,
) {
    fun shareUrl(songId: Long = LISTEN_SHARE_FALLBACK_SONG_ID): String {
        val song = songId.coerceAtLeast(0L)
        return "https://st.music.163.com/listen-together/share/" +
            "?songId=$song&roomId=$roomId&inviterId=$inviterId"
    }
}

/** 双人房只配一位朋友；多人房可以再进几位，并且创建时就要带上当前队列。 */
enum class ListenRoomKind {
    Duo,
    Multi,
}

/** 分享页即使房间还没有队列，也要带一个歌曲 id。 */
const val LISTEN_SHARE_FALLBACK_SONG_ID = 1_372_188_635L

data class ListenParticipant(
    val userId: Long,
    val nickname: String,
    val avatarUrl: String? = null,
)

data class ListenRoomStatus(
    val inRoom: Boolean,
    val roomId: String? = null,
    val participants: List<ListenParticipant> = emptyList(),
)

data class ListenPlaylistVersion(
    val userId: Long,
    val version: Long,
)

data class ListenPlayback(
    val commandType: String,
    val progressMillis: Long,
    val playStatus: String,
    val formerSongId: Long,
    val targetSongId: Long,
    val clientSequence: Long,
    val trackIds: List<Long>,
    val versions: List<ListenPlaylistVersion>,
    val playMode: String,
)

/**
 * 解析官方分享链接、私信里两次百分号编码的深链，以及「房间号 邀请人」这种粘贴残片。
 * 只解一次不够：网易云私信卡片会把 `orpheus://` 再编码一层。
 */
fun parseListenInvite(raw: String): ListenInvite? {
    var candidate = raw.trim().replace("\\&", "&").replace("\\_", "_")
    repeat(3) { candidate = decodePercentAscii(candidate) }

    fun parameter(name: String): String? = Regex(
        "(?i)(?:^|[?&#])${Regex.escape(name)}=([^&#\\s]+)",
    ).find(candidate)?.groupValues?.getOrNull(1)?.takeIf(String::isNotBlank)

    val roomId = parameter("roomId")?.takeIf(::isValidListenRoomId)
    val inviterId = parameter("inviterId")?.toLongOrNull()?.takeIf { it > 0L }
    if (roomId != null && inviterId != null) return ListenInvite(roomId, inviterId)

    val compact = Regex("^\\s*([A-Za-z0-9_-]+)[,\\s:/]+([0-9]+)\\s*$").matchEntire(candidate)
        ?: return null
    val compactRoomId = compact.groupValues[1].takeIf(::isValidListenRoomId) ?: return null
    val compactInviterId = compact.groupValues[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
    return ListenInvite(compactRoomId, compactInviterId)
}

fun listenCreatedRoomId(response: JSONObject): String? =
    response.obj("data")?.obj("roomInfo")?.str("roomId")?.takeIf(::isValidListenRoomId)

/** 业务失败或载荷被拒时抛出，文案已经是能直接给用户看的。 */
fun requireListenSuccess(response: JSONObject) {
    if (response.intCode() != 200) {
        throw IllegalStateException(listenFailureText(response))
    }
    val data = response.obj("data")
    val rejected = data != null && listOf("result", "success").any { key ->
        data.opt(key)?.toString()?.equals("false", ignoreCase = true) == true
    }
    if (rejected) {
        val text = listenFailureText(response)
        throw IllegalStateException(if (text == GENERIC_LISTEN_FAILURE) "一起听请求被拒绝了" else text)
    }
}

/** 房间解散后下一次轮询会看到 488。 */
fun isListenClosed(response: JSONObject): Boolean = response.intCode() == 488

fun listenFailureText(json: JSONObject?): String {
    if (json == null) return "网络不太顺，检查下连接再试"
    val code = json.intCode()
    val raw = listenMessage(json) ?: json.obj("data")?.let(::listenMessage)
    if (!raw.isNullOrEmpty()) return translateListenMessage(raw, code)
    return when (code) {
        301, 302 -> "登录已过期，重新登录一下"
        404, 4050 -> "房间不存在或已解散"
        488 -> "一起听已经结束了"
        250, 400, 403, 405 -> "操作太频繁了，等一下再试"
        in 500..599 -> "网易云服务器忙，稍后再试"
        else -> GENERIC_LISTEN_FAILURE
    }
}

fun incrementListenVersion(
    versions: List<ListenPlaylistVersion>,
    userId: Long,
): List<ListenPlaylistVersion> {
    var found = false
    val updated = versions.map { item ->
        if (item.userId == userId) {
            found = true
            item.copy(version = item.version + 1L)
        } else {
            item
        }
    }
    return if (found) updated else updated + ListenPlaylistVersion(userId, 1L)
}

fun mergeListenVersions(
    local: List<ListenPlaylistVersion>,
    remote: List<ListenPlaylistVersion>,
): List<ListenPlaylistVersion> = (local + remote)
    .groupBy(ListenPlaylistVersion::userId)
    .map { (userId, items) ->
        ListenPlaylistVersion(userId, items.maxOf(ListenPlaylistVersion::version))
    }

fun listenRoomStatus(response: JSONObject): ListenRoomStatus? {
    val data = response.obj("data") ?: return null
    val roomInfo = data.obj("roomInfo")
    val users = roomInfo?.arr("roomUsers") ?: roomInfo?.arr("userList")
    val participants = buildList {
        if (users == null) return@buildList
        for (i in 0 until users.length()) {
            val item = users.optJSONObject(i) ?: continue
            val userId = item.long("userId")?.takeIf { it > 0L }
                ?: item.long("id")?.takeIf { it > 0L }
                ?: continue
            add(
                ListenParticipant(
                    userId = userId,
                    nickname = item.str("nickname") ?: item.str("name").orEmpty(),
                    avatarUrl = (item.str("avatarUrl") ?: item.str("avatar"))?.takeIf { it.isNotBlank() },
                ),
            )
        }
    }
    val roomId = roomInfo?.str("roomId")?.takeIf(::isValidListenRoomId)
        ?: data.str("roomId")?.takeIf(::isValidListenRoomId)
    return ListenRoomStatus(
        inRoom = data.bool("inRoom") ?: (roomId != null),
        roomId = roomId,
        participants = participants,
    )
}

fun listenPlayback(response: JSONObject): ListenPlayback? {
    val data = response.obj("data") ?: return null
    val command = data.obj("playCommand") ?: return null
    val playlist = data.obj("playlist") ?: return null
    val display = playlist.obj("displayList")?.arr("result")
        ?: playlist.arr("displayList")
        ?: playlist.arr("randomList")
        ?: JSONArray()
    val trackIds = buildList {
        for (i in 0 until display.length()) {
            val item = display.opt(i)
            val id = when (item) {
                is JSONObject -> item.long("songId") ?: item.long("id")
                is Number -> item.toLong()
                is String -> item.toLongOrNull()
                else -> null
            }?.takeIf { it > 0L } ?: continue
            if (id !in this) add(id)
        }
    }
    val versionArr = playlist.arr("version")
    val versions = buildList {
        if (versionArr == null) return@buildList
        for (i in 0 until versionArr.length()) {
            val item = versionArr.optJSONObject(i) ?: continue
            val userId = item.long("userId")?.takeIf { it > 0L } ?: continue
            add(ListenPlaylistVersion(userId, item.long("version") ?: 0L))
        }
    }
    return ListenPlayback(
        commandType = command.str("commandType").orEmpty(),
        progressMillis = command.long("progress")?.coerceAtLeast(0L) ?: 0L,
        playStatus = command.str("playStatus").orEmpty(),
        formerSongId = command.long("formerSongId") ?: -1L,
        targetSongId = command.long("targetSongId") ?: command.long("formerSongId") ?: -1L,
        clientSequence = command.long("clientSeq")?.coerceAtLeast(0L) ?: 0L,
        trackIds = trackIds,
        versions = versions,
        playMode = playlist.str("playMode")?.takeIf { it.isNotBlank() } ?: "ORDER_LOOP",
    )
}

fun duoRoomBody(): JSONObject = JSONObject().put("refer", "songplay_more")

/** 多人房按官方客户端的字段播种：当前歌、已播进度、后面的队列。 */
fun multiRoomBody(songId: Long, playedMillis: Long, nextSongIds: List<Long>): JSONObject =
    JSONObject()
        .put("type", "0")
        .put("songId", songId.toString())
        .put("from", "CREATE")
        .put("playedTime", playedMillis.coerceAtLeast(0L).toString())
        .put("groupIds", "[]")
        .put("inviteUids", "[]")
        .put("nextSongIds", JSONArray().apply { nextSongIds.forEach { put(it) } }.toString())
        .put("checkToken", "")

fun acceptBody(roomId: String, inviterId: Long): JSONObject =
    JSONObject()
        .put("refer", "inbox_invite")
        .put("roomId", roomId)
        .put("inviterId", inviterId.toString())

fun playCommandInfo(
    commandType: String,
    progressMillis: Long,
    playStatus: String,
    formerSongId: Long,
    targetSongId: Long,
    clientSeq: Long,
): String = JSONObject()
    .put("commandType", commandType)
    .put("progress", progressMillis.coerceAtLeast(0L))
    .put("playStatus", playStatus)
    .put("formerSongId", formerSongId)
    .put("targetSongId", targetSongId)
    .put("clientSeq", clientSeq.coerceAtLeast(1L))
    .toString()

fun playlistParam(
    commandType: String,
    versions: List<ListenPlaylistVersion>,
    playMode: String,
    displayIds: List<Long>,
    randomIds: List<Long> = displayIds,
): String {
    val version = JSONArray()
    versions.forEach { item ->
        version.put(JSONObject().put("userId", item.userId).put("version", item.version))
    }
    fun strings(ids: List<Long>): JSONArray = JSONArray().apply { ids.forEach { put(it.toString()) } }
    return JSONObject()
        .put("commandType", commandType)
        .put("version", version)
        .put("playMode", playMode)
        .put("anchorSongId", "")
        .put("anchorPosition", -1)
        .put("randomList", strings(randomIds))
        .put("displayList", strings(displayIds))
        .toString()
}

private const val GENERIC_LISTEN_FAILURE = "没能完成，稍后再试"

private fun listenMessage(json: JSONObject): String? =
    listOf("message", "msg", "error", "errMsg").firstNotNullOfOrNull { key ->
        if (!json.has(key) || json.isNull(key)) null
        else json.opt(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    }

private fun translateListenMessage(raw: String, code: Int): String = when {
    raw.contains("登录") || raw.contains("未登录") -> "登录已过期，重新登录一下"
    raw.contains("满") -> "房间满员了，进不去"
    raw.contains("不存在") || raw.contains("解散") || raw.contains("过期") -> "房间不存在或已解散"
    raw.contains("风控") || raw.contains("频繁") || raw.contains("操作过快") -> "操作太频繁了，等一下再试"
    raw.contains("权限") || raw.contains("禁止") -> "当前账号没有一起听的权限"
    raw.contains("会员") || raw.contains("VIP") -> "这个功能需要网易云会员"
    raw.contains("版本") -> "网易云客户端版本太低，升级后再试"
    else -> if (code in 500..599) "网易云服务器忙，稍后再试" else raw
}

fun isValidListenRoomId(value: String): Boolean =
    value.length in 1..256 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }

private fun decodePercentAscii(value: String): String = buildString(value.length) {
    var index = 0
    while (index < value.length) {
        if (value[index] == '%' && index + 2 < value.length) {
            val decoded = value.substring(index + 1, index + 3).toIntOrNull(16)
            if (decoded != null) {
                append(decoded.toChar())
                index += 3
                continue
            }
        }
        append(if (value[index] == '+') ' ' else value[index])
        index += 1
    }
}

private fun JSONObject.intCode(): Int = when (val value = opt("code")) {
    is Number -> value.toInt()
    is String -> value.toIntOrNull() ?: -1
    else -> -1
}

private fun JSONObject.obj(name: String): JSONObject? = optJSONObject(name)

private fun JSONObject.arr(name: String): JSONArray? = optJSONArray(name)

private fun JSONObject.str(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return opt(name)?.toString()?.takeIf { it.isNotEmpty() && it != "null" }
}

private fun JSONObject.long(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    return when (val value = opt(name)) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
}

private fun JSONObject.bool(name: String): Boolean? {
    if (!has(name) || isNull(name)) return null
    return when (val value = opt(name)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when (value.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
        else -> null
    }
}
