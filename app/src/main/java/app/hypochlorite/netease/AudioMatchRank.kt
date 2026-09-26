package app.hypochlorite.netease

import org.json.JSONObject

/**
 * 听歌识曲多候选排序：热歌常被用户私有云盘二次上传，接口会把上传副本和官网曲目
 * 一起丢回来；若只取 [List.first]，很容易落到云盘副本上。
 *
 * 分数越高越优先。信号全部来自歌曲 JSON（识曲薄对象或 /api/v3/song/detail 富对象）：
 * - `pc`：云盘私有上传，强降权
 * - `privilege.st < 0`：无版权/下架
 * - 专辑名带「云盘」
 * - `copyrightId` / 正规专辑 id：官网曲目加分
 */
internal fun audioMatchOfficialScore(song: JSONObject?): Int {
    if (song == null) return Int.MIN_VALUE
    var score = 0

    // 云盘私有上传：song.pc 是对象或非空字符串，官网曲目没有这个字段。
    if (songHasPrivateCloud(song)) score -= 1000

    val album = song.optJSONObject("al") ?: song.optJSONObject("album")
    val albumName = album?.optString("name").orEmpty()
    if (albumName.contains("云盘") || albumName.contains("私人")) score -= 800

    val albumId = album?.opt("id")?.toString()?.toLongOrNull() ?: 0L
    if (albumId > 0L) score += 30
    if (albumName.isNotEmpty()) score += 5

    val privilege = song.optJSONObject("privilege")
    if (privilege != null) {
        val st = privilege.optInt("st", 0)
        if (st < 0) score -= 500
        // privilege 里偶发带着云盘标记
        if (privilege.optBoolean("cs", false)) score -= 400
        val fee = privilege.optInt("fee", song.optInt("fee", -1))
        // fee=4 多见于数字专辑官网轨；0/8 也是常见正版档。仅作轻微加分。
        when (fee) {
            4 -> score += 15
            0, 8, 1 -> score += 8
        }
    } else if (song.has("fee")) {
        when (song.optInt("fee")) {
            4 -> score += 15
            0, 8, 1 -> score += 8
        }
    }

    val copyrightId = song.optLong("copyrightId", 0L)
    if (copyrightId > 0L) score += 25

    // 翻唱 / 伴奏等非原版：有字段时略降，避免压过真正的云盘降权。
    when (song.optInt("originCoverType", 0)) {
        1, 2 -> score -= 40
    }

    val artists = song.optJSONArray("ar") ?: song.optJSONArray("artists")
    if (artists != null && artists.length() > 0) score += 5

    return score
}

internal fun songHasPrivateCloud(song: JSONObject): Boolean {
    if (!song.has("pc") || song.isNull("pc")) return false
    // pc 可能是 JSONObject，也可能是历史形态的字符串。
    song.optJSONObject("pc")?.let { return true }
    return song.optString("pc").isNotEmpty()
}

/**
 * 按 [audioMatchOfficialScore] 稳定降序。同权保留接口原序（相关度）。
 */
internal fun preferOfficialAudioMatchHits(
    hits: List<AudioMatchHit>,
    songJsonById: Map<String, JSONObject>,
): List<AudioMatchHit> {
    if (hits.size <= 1) return hits
    return hits
        .mapIndexed { index, hit ->
            Triple(hit, audioMatchOfficialScore(songJsonById[hit.song.id]), index)
        }
        .sortedWith(
            compareByDescending<Triple<AudioMatchHit, Int, Int>> { it.second }
                .thenBy { it.third },
        )
        .map { it.first }
}
