package app.hypochlorite.netease

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一页 cloudsearch 结果。
 *
 * [total] 为 -1 表示这次响应没带计数，调用方只能靠「这一页是否满」判断还有没有下一页。
 */
data class SearchPage<T>(val items: List<T>, val total: Int)

internal fun parseArtistSearch(json: JSONObject, limit: Int): SearchPage<Artist> {
    val result = json.optJSONObject("result") ?: return SearchPage(emptyList(), 0)
    val arr = result.optJSONArray("artists")
    val total = reportedCount(result, "artistCount")
    if (arr == null || limit <= 0) return SearchPage(emptyList(), total)
    return SearchPage(readLimited(arr, limit, ::artistFromSearch), total)
}

internal fun parseAlbumSearch(json: JSONObject, limit: Int): SearchPage<Album> {
    val result = json.optJSONObject("result") ?: return SearchPage(emptyList(), 0)
    val arr = result.optJSONArray("albums")
    val total = reportedCount(result, "albumCount")
    if (arr == null || limit <= 0) return SearchPage(emptyList(), total)
    return SearchPage(readLimited(arr, limit, ::albumFromSearch), total)
}

internal fun parsePlaylistSearch(json: JSONObject, limit: Int): SearchPage<Playlist> {
    val result = json.optJSONObject("result") ?: return SearchPage(emptyList(), 0)
    val arr = result.optJSONArray("playlists")
    val total = reportedCount(result, "playlistCount")
    if (arr == null || limit <= 0) return SearchPage(emptyList(), total)
    return SearchPage(readLimited(arr, limit, ::playlistFromSearch), total)
}

private fun reportedCount(result: JSONObject, key: String): Int =
    if (result.has(key)) result.optInt(key, 0) else -1

private fun <T> readLimited(arr: JSONArray, limit: Int, map: (JSONObject?) -> T?): List<T> {
    val out = ArrayList<T>(minOf(arr.length(), limit))
    for (i in 0 until minOf(arr.length(), limit)) {
        map(arr.optJSONObject(i))?.let { out.add(it) }
    }
    return out
}

private fun artistFromSearch(obj: JSONObject?): Artist? {
    if (obj == null) return null
    val id = positiveId(obj) ?: return null
    val name = obj.optString("name").trim()
    if (name.isEmpty()) return null
    return Artist(
        id = id,
        name = name,
        cover = obj.optString("picUrl").ifEmpty { obj.optString("img1v1Url") },
        alias = firstAlias(obj.optJSONArray("alias")),
        musicSize = obj.optInt("musicSize", obj.optInt("songSize", 0)),
        albumSize = obj.optInt("albumSize", 0),
    )
}

private fun albumFromSearch(obj: JSONObject?): Album? {
    if (obj == null) return null
    val id = positiveId(obj) ?: return null
    val name = obj.optString("name").trim()
    if (name.isEmpty()) return null
    val artist = obj.optJSONObject("artist") ?: obj.optJSONArray("artists")?.optJSONObject(0)
    return Album(
        id = id,
        name = name,
        cover = obj.optString("picUrl").ifEmpty { obj.optString("blurPicUrl") },
        artistName = artist?.optString("name").orEmpty(),
        artistId = artist?.let(::positiveId),
        songCount = obj.optInt("size", obj.optInt("songCount", 0)),
        publishTime = obj.optLong("publishTime", 0),
        description = obj.optString("description").ifEmpty { obj.optString("briefDesc") },
    )
}

private fun playlistFromSearch(obj: JSONObject?): Playlist? {
    if (obj == null) return null
    val id = positiveId(obj) ?: return null
    val name = obj.optString("name").trim()
    if (name.isEmpty()) return null
    val creator = obj.optJSONObject("creator")
    return Playlist(
        id = id,
        name = name,
        cover = obj.optString("coverImgUrl").ifEmpty { obj.optString("picUrl") },
        trackCount = obj.optInt("trackCount", 0),
        creatorId = creator?.opt("userId")?.toString()?.takeIf { it.isNotEmpty() && it != "0" },
        subscribed = obj.optBoolean("subscribed", false),
        specialType = obj.optInt("specialType", 0),
        creatorName = creator?.optString("nickname").orEmpty(),
    )
}

private fun positiveId(obj: JSONObject): String? =
    obj.opt("id")?.toString()?.takeIf { it.isNotEmpty() && it != "0" && it != "null" }

private fun firstAlias(arr: JSONArray?): String {
    if (arr == null) return ""
    for (i in 0 until arr.length()) {
        when (val value = arr.opt(i)) {
            is String -> {
                val trimmed = value.trim()
                if (trimmed.isNotEmpty()) return trimmed
            }
            is JSONObject -> {
                val name = value.optString("name").trim()
                if (name.isNotEmpty()) return name
            }
        }
    }
    return ""
}
