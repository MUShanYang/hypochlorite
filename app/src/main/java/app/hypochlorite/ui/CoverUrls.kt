package app.hypochlorite.ui

/**
 * 网易 CDN 封面直链的尺寸约定。
 *
 * `?param=WxH` 是网易图床自己的缩放参数，写进 URL 才能让 CDN 吐小图，
 * 而不是先把原图拉下来再在本地缩。非网易域名原样返回，避免给别的图床塞它不认识的参数。
 */
object CoverUrls {
    /** 全屏虚化背景用：够糊、够小，不必原图 */
    const val BACKDROP_PX = 400

    fun sized(url: String, px: Int = BACKDROP_PX): String {
        if (url.isEmpty() || px <= 0) return url
        if (!isNeteaseCdn(url)) return url
        val hash = url.indexOf('#')
        val (withoutFrag, frag) = if (hash >= 0) {
            url.substring(0, hash) to url.substring(hash)
        } else {
            url to ""
        }
        val q = withoutFrag.indexOf('?')
        val path = if (q >= 0) withoutFrag.substring(0, q) else withoutFrag
        val query = if (q >= 0) withoutFrag.substring(q + 1) else ""
        val kept = query.split('&').filter { it.isNotEmpty() && !it.startsWith("param=") }
        return path + "?" + (kept + "param=${px}y$px").joinToString("&") + frag
    }

    fun isNeteaseCdn(url: String): Boolean {
        val hostStart = url.indexOf("://").let { if (it >= 0) it + 3 else 0 }
        val hostEnd = url.indexOf('/', hostStart).let { if (it >= 0) it else url.length }
        val host = url.substring(hostStart, hostEnd).lowercase()
        return host.contains("music.126.net") || host.contains("music.163.com")
    }
}
