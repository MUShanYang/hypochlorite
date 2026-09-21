package app.hypochlorite.netease

object SongId {
    private val bareId = Regex("^\\d{1,18}$")
    private val queryId = Regex("[?&]id=(\\d{1,18})")
    private val pathSongId = Regex("/song/(\\d{1,18})")
    private val otherResource = Regex("/(playlist|album|artist|dj|program|mv)(/|\\?)")

    fun parse(input: String?): String? {
        val s = input?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.matches(bareId)) return s
        val flattened = s.replace("/#/", "/").replace("#/", "/")
        val path = flattened.lowercase()
        val isSongUrl = path.contains("/song")
        if (!isSongUrl && otherResource.containsMatchIn(path)) return null
        queryId.find(flattened)?.groupValues?.get(1)?.let { return it }
        pathSongId.find(flattened)?.groupValues?.get(1)?.let { return it }
        return null
    }
}
