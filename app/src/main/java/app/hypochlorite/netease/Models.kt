package app.hypochlorite.netease

import androidx.compose.runtime.Immutable

@Immutable
data class Song(
    val id: String,
    val name: String,
    val artists: List<String> = emptyList(),
    val album: String = "",
    val albumId: String? = null,
    val durationMs: Long = 0,
    val cover: String = "",
) {
    fun line(): String {
        val a = artists.joinToString(" / ")
        return if (a.isNotEmpty()) "$name — $a" else name.ifEmpty { "id $id" }
    }
}

@Immutable
data class Album(
    val id: String,
    val name: String,
    val cover: String = "",
    val artistName: String = "",
    val artistId: String? = null,
    val songCount: Int = 0,
    val publishTime: Long = 0,
    val description: String = "",
)

/**
 * 搜索命中的歌手。
 *
 * cloudsearch 的歌手对象不带粉丝数，综合页只展示曲目数和专辑数。
 */
@Immutable
data class Artist(
    val id: String,
    val name: String,
    val cover: String = "",
    val alias: String = "",
    val musicSize: Int = 0,
    val albumSize: Int = 0,
)

@Immutable
data class Playlist(
    val id: String,
    val name: String,
    val cover: String = "",
    val trackCount: Int = 0,
    val creatorId: String? = null,
    val subscribed: Boolean = false,
    val specialType: Int = 0,
    val creatorName: String = "",
)

data class Profile(
    val userId: String? = null,
    val nickname: String? = null,
    val avatarUrl: String? = null,
    val vipType: Int = 0,
)

data class Playable(
    val songId: String,
    val playUrl: String?,
    val br: Int? = null,
    val level: String? = null,
    val type: String? = null,
    val via: String? = null,
    val trial: Boolean = false,
    val code: Int? = null,
    /**
     * 这条流的采样率（Hz），取自接口返回的 `sr` —— 之前这个字段被丢掉了。
     *
     * 它是「避免系统重采样」那一组功能的关键输入：AudioFlinger 只在
     * **数据采样率 == 输出设备原生采样率**时才不做 SRC，所以要拿它去比对。
     * `null` = 接口没报（部分老接口 / 试听片段会有），此时不参与匹配判断。
     */
    val sampleRate: Int? = null,
    /** 这条流的位深（接口 `mdl` 之类），只用于展示，不做任何格式改写 */
    val bitDepth: Int? = null,
)

@Immutable
data class LyricLine(
    val timeMs: Long,
    val text: String,
)

/** 一起听房间里的一个成员 */
@Immutable
data class RoomUser(
    val userId: String,
    val nickname: String = "",
    val avatarUrl: String = "",
)

/**
 * 一起听房间快照。
 *
 * [roomId] 是不透明的房间号。[inviterId] 是邀请链接上的那个人，一般就是房主。
 * [ownerId] 用来在成员列表里标出房主。
 */
@Immutable
data class RoomInfo(
    val roomId: String,
    val ownerId: String? = null,
    val inviterId: String? = null,
    val users: List<RoomUser> = emptyList(),
    val songId: String? = null,
    val playStatus: String? = null,
    val progressMs: Long = 0L,
) {
    val memberCount: Int get() = users.size
}
