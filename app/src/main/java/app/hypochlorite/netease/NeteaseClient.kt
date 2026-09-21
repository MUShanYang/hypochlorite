package app.hypochlorite.netease

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 网易云接口客户端 —— api-enhanced 的**模块层内建移植**。
 *
 * 传输层在 [Ncm]（api-enhanced `util/request.js` 的 Kotlin 移植，app 自己直连网易，
 * 不连任何外部服务），这里等价于参考项目的 module 目录下各模块文件：每个方法 =
 * 一个端点的「uri + 加密通道 + 参数」对照实现。端点、加密方式、参数形状都按
 * 参考项目的模块文件逐一对齐，响应就是网易的原始 JSON，所以 parse / normalize
 * 的形状与早期直连版完全一致，调用方无感。
 *
 * 对照表（方法 → uri / 通道，想改接口先看参考项目 module 目录里同名模块）：
 * | 方法 | uri | 通道 |
 * |---|---|---|
 * | sendSmsCaptcha | /api/sms/captcha/sent | weapi（被拦时明文兜底） |
 * | loginWith* | /api/w/login/cellphone（被拦时明文 /api/login/cellphone 兜底） | weapi → api |
 * | qrUnikey | /api/login/qrcode/unikey | eapi |
 * | qrStatus | /api/login/qrcode/client/login | eapi |
 * | refreshAccount | /api/nuser/account/get | weapi |
 * | getSongDetail(s) | /api/v3/song/detail | weapi |
 * | search* | /api/cloudsearch/pc | eapi |
 * | userPlaylists | /api/user/playlist | weapi |
 * | playlistDetail | /api/v6/playlist/detail | eapi |
 * | albumDetail | /api/v1/album/:id | weapi |
 * | artistSongsPaged | /api/v1/artist/songs（top/song 兜底） | eapi / weapi |
 * | lyric | /api/song/lyric/v1（lyric 兜底） | eapi |
 * | dailySongs / dailyPlaylists / personalFm / simiSongs | discovery/radio 系列 | weapi |
 * | likeSong | /api/radio/like（song/like 兜底） | weapi / eapi |
 * | manipulatePlaylistTracks | /api/playlist/manipulate/tracks | eapi |
 * | likeList | /api/song/like/get | eapi |
 * | 一起听 lt* | /api/listen/together/ 各端点 | eapi（status 走 weapi） |
 * | getPlayerUrl | /api/song/enhance/player/url/v1 等 | eapi → weapi → api |
 *
 * 参考项目的 `/song/url/v1` 走 xeapi（需要服务端公钥握手，见 [Ncm] 头注释），
 * 这里按旧版直连的验证结果走 eapi 同一后端路径 —— 拿到的 data[0] 结构一致。
 */
class NeteaseClient(
    private val session: SessionStore,
    private val http: OkHttpClient = defaultHttp(),
) {
    var fetchBr: Int = 320000
    var fetchLevel: String = "exhigh"

    fun setQuality(preset: QualityPreset) {
        fetchBr = preset.fetchBr
        fetchLevel = preset.fetchLevel
    }

    /** 端点调用统一入口。 */
    private fun ncm(
        uri: String,
        data: JSONObject,
        crypto: String = "",
        fakeNmtid: Boolean = true,
        plainFallback: Boolean = true,
    ): Ncm.Res = Ncm.request(http, session, uri, data, crypto, fakeNmtid, plainFallback = plainFallback)

    // ------------------------------------------------------------------ 通用解析

    private fun normalizeSong(song: JSONObject?): Song? {
        if (song == null) return null
        val artistsSrc = song.optJSONArray("ar") ?: song.optJSONArray("artists")
        val artists = mutableListOf<String>()
        if (artistsSrc != null) {
            for (i in 0 until artistsSrc.length()) {
                val n = artistsSrc.optJSONObject(i)?.optString("name").orEmpty()
                if (n.isNotEmpty()) artists.add(n)
            }
        }
        val album = song.optJSONObject("al") ?: song.optJSONObject("album") ?: JSONObject()
        val albumId = album.opt("id")?.toString()?.takeIf { it != "0" && it.isNotEmpty() }
        var cover = album.optString("picUrl").ifEmpty {
            album.optString("blurPicUrl").ifEmpty {
                song.optString("picUrl").ifEmpty {
                    song.optString("blurPicUrl")
                }
            }
        }
        if (cover.isEmpty()) {
            val arObj = (song.optJSONArray("ar") ?: song.optJSONArray("artists"))?.optJSONObject(0)
            cover = arObj?.optString("picUrl")?.ifEmpty { arObj.optString("img1v1Url") }.orEmpty()
        }
        return Song(
            id = song.opt("id")?.toString() ?: return null,
            name = song.optString("name"),
            artists = artists,
            album = album.optString("name"),
            albumId = albumId,
            durationMs = song.optLong("dt", song.optLong("duration", 0)),
            cover = cover,
        )
    }

    private fun normalizePlaylist(pl: JSONObject?): Playlist? {
        if (pl == null) return null
        val id = pl.opt("id")?.toString() ?: return null
        val cover = pl.optString("coverImgUrl").ifEmpty { pl.optString("picUrl") }
        val creator = pl.optJSONObject("creator")
        return Playlist(
            id = id,
            name = pl.optString("name"),
            cover = cover,
            trackCount = pl.optInt("trackCount", pl.optJSONArray("tracks")?.length() ?: 0),
            creatorId = creator?.opt("userId")?.toString() ?: pl.opt("userId")?.toString(),
            subscribed = pl.optBoolean("subscribed", false),
            specialType = pl.optInt("specialType", 0),
        )
    }

    private fun playerDatum(json: JSONObject?): JSONObject? {
        if (json == null) return null
        val data = json.opt("data")
        if (data is JSONArray) return data.optJSONObject(0)
        if (data is JSONObject) return data
        return null
    }

    private fun packPlayer(json: JSONObject?, songId: String, via: String, status: Int): Playable {
        val datum = playerDatum(json)
        val url = datum?.optString("url")?.ifEmpty { null }
        return Playable(
            songId = songId,
            playUrl = url,
            br = if (datum?.has("br") == true) datum.optInt("br") else null,
            level = datum?.optString("level")?.ifEmpty { null },
            type = datum?.optString("type")?.ifEmpty { null },
            via = via,
            trial = datum?.optJSONObject("freeTrialInfo") != null,
            code = datum?.optInt("code", json?.optInt("code", status) ?: status) ?: json?.optInt("code", status),
            // 采样率是「避免重采样」功能的输入，之前一直被丢掉。
            // 接口在部分档位上不报，拿不到就留 null，让上层跳过匹配判断而不是瞎猜。
            sampleRate = datum?.optInt("sr", 0)?.takeIf { it > 0 },
            bitDepth = datum?.optInt("mdl", 0)?.takeIf { it > 0 },
        )
    }

    private fun usable(p: Playable) = !p.playUrl.isNullOrEmpty() && !p.trial

    // ------------------------------------------------------------------ 播放地址

    /**
     * 取播放地址。四级降级（eapi v1 → eapi 下载通道 → weapi v1 → 明文通道），
     * 都空了再落回免登录的 outer media 直链（见 [resolvePlayable]）。
     * 音质档位 level 与 [Quality] 的取值同名直传。
     */
    fun getPlayerUrl(songId: String): Playable {
        var last: Playable? = null
        var trial: Playable? = null
        fun take(p: Playable): Playable? {
            last = p
            if (usable(p)) return p
            if (!p.playUrl.isNullOrEmpty() && p.trial && trial == null) trial = p
            return null
        }

        val idNum = songId.toLongOrNull() ?: 0

        take(
            packPlayer(
                ncm(
                    "/api/song/enhance/player/url/v1",
                    JSONObject()
                        .put("ids", JSONArray().put(idNum).toString())
                        .put("level", fetchLevel)
                        .put("encodeType", "flac")
                        .also { if (fetchLevel == "sky") it.put("immerseType", "c51") },
                    "eapi",
                ).json,
                songId,
                "eapi-player-url-v1",
                200,
            ),
        )?.let { return it }

        take(
            packPlayer(
                ncm(
                    "/api/song/enhance/download/url/v1",
                    JSONObject()
                        .put("id", idNum)
                        .put("immerseType", "c51")
                        .put("level", fetchLevel),
                    "eapi",
                ).json,
                songId,
                "eapi-download-url-v1",
                200,
            ),
        )?.let { return it }

        take(
            packPlayer(
                ncm(
                    "/api/song/enhance/player/url/v1",
                    JSONObject()
                        .put("ids", JSONArray().put(idNum).toString())
                        .put("level", fetchLevel)
                        .put("encodeType", "flac"),
                    "weapi",
                ).json,
                songId,
                "weapi-player-url-v1",
                200,
            ),
        )?.let { return it }

        take(
            packPlayer(
                ncm(
                    "/api/song/enhance/player/url",
                    JSONObject()
                        .put("id", idNum)
                        .put("ids", JSONArray().put(idNum).toString())
                        .put("br", fetchBr),
                    "api",
                ).json,
                songId,
                "api-player-url",
                200,
            ),
        )?.let { return it }

        return trial ?: last ?: Playable(songId = songId, playUrl = null)
    }

    fun resolvePlayable(songId: String): Playable {
        val player = getPlayerUrl(songId)
        if (!player.playUrl.isNullOrEmpty()) return player
        return player.copy(playUrl = Crypto.outerMediaUrl(songId), via = "outer-url-fallback")
    }

    // ------------------------------------------------------------------ 歌曲 / 搜索

    fun getSongDetail(songId: String): Song? {
        val idNum = songId.toLongOrNull() ?: return null
        val res = ncm(
            "/api/v3/song/detail",
            JSONObject()
                .put("c", JSONArray().put(JSONObject().put("id", idNum)).toString())
                .put("ids", JSONArray().put(idNum).toString()),
            "weapi",
        )
        val song = res.json?.optJSONArray("songs")?.optJSONObject(0)
        return normalizeSong(song)
    }

    fun getSongDetails(ids: List<String>): List<Song> {
        val idNums = ids.mapNotNull { it.toLongOrNull() }
        if (idNums.isEmpty()) return emptyList()
        val cArr = JSONArray()
        val idsArr = JSONArray()
        for (id in idNums) {
            cArr.put(JSONObject().put("id", id).put("v", 0))
            idsArr.put(id)
        }
        val res = ncm(
            "/api/v3/song/detail",
            JSONObject().put("c", cArr.toString()).put("ids", idsArr.toString()),
            "weapi",
        )
        val arr = res.json?.optJSONArray("songs")
        val out = mutableListOf<Song>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                normalizeSong(arr.optJSONObject(i))?.let { out.add(it) }
            }
        }
        return out
    }

    fun searchSongs(query: String, limit: Int = 20): List<Song> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        SongId.parse(q)?.let { id ->
            getSongDetail(id)?.let { return listOf(it) }
        }
        val res = ncm(
            "/api/cloudsearch/pc",
            JSONObject()
                .put("s", q)
                .put("type", 1)
                .put("limit", limit)
                .put("offset", 0)
                .put("total", true),
        )
        val songs = res.json?.optJSONObject("result")?.optJSONArray("songs")
        val out = mutableListOf<Song>()
        if (songs != null) {
            for (i in 0 until minOf(songs.length(), limit)) {
                normalizeSong(songs.optJSONObject(i))?.let { out.add(it) }
            }
        }
        if (out.any { it.cover.isEmpty() }) {
            val missingIds = out.filter { it.cover.isEmpty() }.map { it.id }
            val details = runCatching { getSongDetails(missingIds) }.getOrDefault(emptyList()).associateBy { it.id }
            for (i in out.indices) {
                if (out[i].cover.isEmpty()) {
                    details[out[i].id]?.let { det ->
                        if (det.cover.isNotEmpty()) out[i] = out[i].copy(cover = det.cover)
                    }
                }
            }
        }
        return out
    }

    fun searchPlaylists(query: String, limit: Int = 20): List<Playlist> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val res = ncm(
            "/api/cloudsearch/pc",
            JSONObject()
                .put("s", q)
                .put("type", 1000)
                .put("limit", limit)
                .put("offset", 0)
                .put("total", true),
        )
        val lists = res.json?.optJSONObject("result")?.optJSONArray("playlists")
        val out = mutableListOf<Playlist>()
        if (lists != null) {
            for (i in 0 until minOf(lists.length(), limit)) {
                normalizePlaylist(lists.optJSONObject(i))?.let { out.add(it) }
            }
        }
        return out
    }

    fun userPlaylists(uid: String, limit: Int = 1000): List<Playlist> {
        val res = ncm(
            "/api/user/playlist",
            JSONObject()
                .put("uid", uid.toLongOrNull() ?: 0)
                .put("limit", limit)
                .put("offset", 0)
                .put("includeVideo", true),
            "weapi",
        )
        val arr = res.json?.optJSONArray("playlist")
        val out = mutableListOf<Playlist>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                normalizePlaylist(arr.optJSONObject(i))?.let { out.add(it) }
            }
        }
        return out
    }

    fun playlistDetail(playlistId: String): Pair<Playlist?, List<Song>> {
        val idNum = playlistId.toLongOrNull() ?: return null to emptyList()
        val res = ncm(
            "/api/v6/playlist/detail",
            JSONObject().put("id", idNum).put("n", 100000).put("s", 8),
        )
        val pl = res.json?.optJSONObject("playlist") ?: return null to emptyList()
        val tracks = pl.optJSONArray("tracks")
        val songs = mutableListOf<Song>()
        if (tracks != null) {
            for (i in 0 until tracks.length()) {
                normalizeSong(tracks.optJSONObject(i))?.let { songs.add(it) }
            }
        }
        if (songs.isEmpty()) {
            val ids = pl.optJSONArray("trackIds")
            if (ids != null && ids.length() > 0) {
                val idNums = mutableListOf<Long>()
                for (i in 0 until minOf(ids.length(), 200)) {
                    val item = ids.opt(i)
                    val idVal = when (item) {
                        is JSONObject -> item.opt("id")
                        is Number -> item
                        is String -> item.toLongOrNull()
                        else -> null
                    }
                    idVal?.toString()?.toLongOrNull()?.let { idNums.add(it) }
                }
                if (idNums.isNotEmpty()) {
                    getSongDetails(idNums.map { it.toString() }).let { songs.addAll(it) }
                }
            }
        }
        if (songs.isEmpty()) return normalizePlaylist(pl) to emptyList()
        return normalizePlaylist(pl) to songs
    }

    fun searchArtist(query: String): Pair<String?, String?> {
        val q = query.trim()
        if (q.isEmpty()) return null to null
        val res = ncm(
            "/api/cloudsearch/pc",
            JSONObject().put("s", q).put("type", 100).put("limit", 1).put("offset", 0).put("total", true),
        )
        val first = res.json?.optJSONObject("result")?.optJSONArray("artists")?.optJSONObject(0)
        val id = first?.opt("id")?.toString()
        val pic = first?.optString("picUrl")?.ifEmpty { first.optString("img1v1Url") }?.ifEmpty { null }
        return id to pic
    }

    fun searchAlbum(query: String): Pair<String?, String?> {
        val q = query.trim()
        if (q.isEmpty()) return null to null
        val res = ncm(
            "/api/cloudsearch/pc",
            JSONObject().put("s", q).put("type", 10).put("limit", 1).put("offset", 0).put("total", true),
        )
        val first = res.json?.optJSONObject("result")?.optJSONArray("albums")?.optJSONObject(0)
        val id = first?.opt("id")?.toString()
        val pic = first?.optString("picUrl")?.ifEmpty { first.optString("blurPicUrl") }?.ifEmpty { null }
        return id to pic
    }

    fun albumDetail(albumId: String): Pair<Album?, List<Song>> {
        val idNum = albumId.toLongOrNull() ?: return null to emptyList()
        val json = ncm("/api/v1/album/$idNum", JSONObject(), "weapi").json
        if (json == null || json.optInt("code", 200) != 200) return null to emptyList()
        val albObj = json.optJSONObject("album")
        val songsArr = json.optJSONArray("songs")
        val songs = mutableListOf<Song>()
        if (songsArr != null) {
            for (i in 0 until songsArr.length()) {
                normalizeSong(songsArr.optJSONObject(i))?.let { songs.add(it) }
            }
        }
        val album = if (albObj != null) {
            val arObj = albObj.optJSONObject("artist") ?: albObj.optJSONArray("artists")?.optJSONObject(0)
            Album(
                id = albObj.opt("id")?.toString() ?: albumId,
                name = albObj.optString("name"),
                cover = albObj.optString("picUrl").ifEmpty { albObj.optString("blurPicUrl") },
                artistName = arObj?.optString("name").orEmpty(),
                artistId = arObj?.opt("id")?.toString(),
                songCount = songs.size,
                publishTime = albObj.optLong("publishTime", 0),
                description = albObj.optString("description"),
            )
        } else null
        return album to songs
    }

    fun artistSongsPaged(artistId: String, offset: Int = 0, limit: Int = 100): Triple<List<Song>, Boolean, Int> {
        val idNum = artistId.toLongOrNull() ?: return Triple(emptyList(), false, 0)
        val json = ncm(
            "/api/v1/artist/songs",
            JSONObject()
                .put("id", idNum)
                .put("private_cloud", "true")
                .put("work_type", 1)
                .put("order", "hot")
                .put("offset", offset)
                .put("limit", limit),
        ).json
        var arr = json?.optJSONArray("songs")
        val hasMore = json?.optBoolean("more", false) ?: false
        val total = json?.optInt("total", 0) ?: 0

        if ((arr == null || arr.length() == 0) && offset == 0) {
            arr = ncm("/api/artist/top/song", JSONObject().put("id", idNum), "weapi").json?.optJSONArray("songs")
        }

        val out = mutableListOf<Song>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                normalizeSong(arr.optJSONObject(i))?.let { out.add(it) }
            }
        }
        if (out.any { it.cover.isEmpty() }) {
            val missingIds = out.filter { it.cover.isEmpty() }.map { it.id }
            val details = runCatching { getSongDetails(missingIds) }.getOrDefault(emptyList()).associateBy { it.id }
            for (i in out.indices) {
                if (out[i].cover.isEmpty()) {
                    details[out[i].id]?.let { det ->
                        if (det.cover.isNotEmpty()) out[i] = out[i].copy(cover = det.cover)
                    }
                }
            }
        }
        val effTotal = if (total > 0) total else out.size
        return Triple(out, hasMore, effTotal)
    }

    fun artistSongs(artistId: String): List<Song> {
        return artistSongsPaged(artistId, 0, 100).first
    }

    fun lyric(songId: String): List<LyricLine> {
        val idNum = songId.toLongOrNull() ?: return emptyList()
        // lyric_new（含逐字歌词字段位）：参考项目 module/lyric_new.js
        var json = ncm(
            "/api/song/lyric/v1",
            JSONObject()
                .put("id", idNum)
                .put("cp", false)
                .put("tv", 0).put("lv", 0).put("rv", 0).put("kv", 0)
                .put("yv", 0).put("ytv", 0).put("yrv", 0),
        ).json
        if (json == null || json.optJSONObject("lrc") == null) {
            // 旧版歌词通道兜底：module/lyric.js
            json = ncm(
                "/api/song/lyric",
                JSONObject()
                    .put("id", idNum)
                    .put("tv", -1).put("lv", -1).put("rv", -1).put("kv", -1)
                    .put("_nmclfl", 1),
            ).json
        }
        val raw = json?.optJSONObject("lrc")?.optString("lyric").orEmpty()
        if (raw.isEmpty()) return emptyList()
        return parseLrc(raw)
    }

    // ------------------------------------------------------------------ 推荐 / FM

    fun dailySongs(): List<Song> {
        val arr = ncm("/api/v3/discovery/recommend/songs", JSONObject(), "weapi")
            .json?.optJSONObject("data")?.optJSONArray("dailySongs")
        val out = mutableListOf<Song>()
        if (arr != null) {
            for (i in 0 until arr.length()) normalizeSong(arr.optJSONObject(i))?.let { out.add(it) }
        }
        return out
    }

    fun dailyPlaylists(): List<Playlist> {
        val arr = ncm("/api/v1/discovery/recommend/resource", JSONObject(), "weapi").json?.optJSONArray("recommend")
        val out = mutableListOf<Playlist>()
        if (arr != null) {
            for (i in 0 until arr.length()) normalizePlaylist(arr.optJSONObject(i))?.let { out.add(it) }
        }
        return out
    }

    fun personalFm(): List<Song> {
        val arr = ncm("/api/v1/radio/get", JSONObject(), "weapi").json?.optJSONArray("data")
        val out = mutableListOf<Song>()
        if (arr != null) {
            for (i in 0 until arr.length()) normalizeSong(arr.optJSONObject(i))?.let { out.add(it) }
        }
        return out
    }

    fun simiSongs(songId: String): List<Song> {
        val res = ncm(
            "/api/v1/discovery/simiSong",
            JSONObject().put("songid", songId.toLongOrNull() ?: 0).put("limit", 50).put("offset", 0),
            "weapi",
        )
        val arr = res.json?.optJSONArray("songs")
        val out = mutableListOf<Song>()
        if (arr != null) {
            for (i in 0 until arr.length()) normalizeSong(arr.optJSONObject(i))?.let { out.add(it) }
        }
        return out
    }

    // ------------------------------------------------------------------ 登录

    /**
     * 取 -462 响应里的验证页地址。
     *
     * **必须优先用 `data.url`** —— `data` 里同时有 `verifyUrl` 和 `url` 两个字段，
     * 而 `verifyUrl` 是**不带任何参数的裸地址**。验证页是从 `location.search`
     * 里读配置的，拿裸地址打开等于给它一个空配置，页面连 verifyId 都不知道。
     * （详见 memory topics/09。）
     */
    private fun verifyPageUrl(json: JSONObject?): String {
        val data = json?.optJSONObject("data") ?: return ""
        return data.optString("url").ifEmpty { data.optString("verifyUrl") }
    }

    /** 下发短信验证码。返回 (code, message)，code 200 才算成功。 */
    fun sendSmsCaptcha(phone: String, countryCode: String = "86"): Pair<Int, String> {
        val data = JSONObject()
            .put("ctcode", countryCode)
            .put("secrete", "music_middleuser_pclogin")
            .put("cellphone", phone)
        // 明文兜底（通道级拦截重发）内置在 Ncm.request 里，同 uri 同参数直接可用
        val res = ncm("/api/sms/captcha/sent", data, "weapi", fakeNmtid = false)
        val json = res.json
        val code = json?.optInt("code", 0) ?: 0
        val msg = json?.optString("message")?.ifEmpty { null }
            ?: json?.optString("msg")?.ifEmpty { null }
            ?: ""
        lastLoginCode = code
        if (json == null) {
            return code to when {
                res.status == 0 -> "网络不可用"
                res.bodyBlank -> "网络出口被网易拦了，换个网络再试"
                else -> "服务没有回应，稍后再试"
            }
        }
        if (code == 200) return 200 to ""
        if (code == -462) {
            lastLoginVerifyUrl = verifyPageUrl(json)
            return code to "需要先过一次网易的安全验证"
        }
        // 只有真的撞上风控才说风控，别把「手机号格式不对」也说成风控
        val friendly = when {
            code == 400 -> "手机号格式不对"
            code == 501 -> "刚发过，等一会儿再试"
            code == 503 || code == 460 || msg.contains("网络环境异常") -> "被网易风控拦了，稍后再试"
            msg.isNotEmpty() -> msg
            else -> "发送失败 ($code)"
        }
        return code to friendly
    }

    /**
     * 登录类请求的主备通道：主路 weapi，**响应连 JSON 都解不出来时**换明文通道
     * 再发一次（见 [loginExchange]）。
     *
     * 明文通道的端点也换回明文形态 `/api/login/cellphone`，参数形状按明文端点的
     * 老规矩（`rememberLogin`，不带 weapi 特有的 type/secureCaptcha/https）。
     */
    private fun loginExchange(weapiData: JSONObject, plainData: JSONObject): Ncm.Res {
        // 明文兜底关掉（plainFallback=false）：登录的明文端点 uri 和参数形状都不同，
        // 不能让 Ncm 用 /api/w/login/cellphone 原样打明文通道
        var res = ncm("/api/w/login/cellphone", weapiData, "weapi", fakeNmtid = false, plainFallback = false)
        if (res.json == null && res.status != 0) {
            res = Ncm.request(
                http, session, "/api/login/cellphone", plainData, "api", fakeNmtid = false, domain = Ncm.DOMAIN,
            )
        }
        return res
    }

    /**
     * 手机号 + 密码登录。密码在客户端 MD5 后传（参考项目用 `password` 字段收 MD5 值）。
     *
     * [refreshTicket] 是直连时代的遗留参数，收下但什么都不做 —— 调用方
     * （ViewModel 的 afterVerify 流程）不用跟着改。登录请求**不伪造 NMTID**。
     */
    fun loginWithPassword(
        phone: String,
        password: String,
        countryCode: String = "86",
        refreshTicket: Boolean = true,
    ): Profile? {
        val md5 = Crypto.md5Hex(password)
        val res = loginExchange(
            weapiData = JSONObject()
                .put("type", "1")
                .put("https", "true")
                .put("phone", phone)
                .put("countrycode", countryCode)
                .put("password", md5)
                .put("remember", "true")
                .put("secureCaptcha", ""),
            plainData = JSONObject()
                .put("phone", phone)
                .put("countrycode", countryCode)
                .put("password", md5)
                .put("rememberLogin", "true"),
        )
        return finishCellphoneLogin(res)
    }

    /** 手机号 + 短信验证码登录。[refreshTicket] 同上，占位。 */
    fun loginWithCaptcha(
        phone: String,
        captcha: String,
        countryCode: String = "86",
        refreshTicket: Boolean = true,
    ): Profile? {
        val res = loginExchange(
            weapiData = JSONObject()
                .put("type", "1")
                .put("https", "true")
                .put("phone", phone)
                .put("countrycode", countryCode)
                .put("captcha", captcha)
                .put("remember", "true")
                .put("secureCaptcha", ""),
            plainData = JSONObject()
                .put("phone", phone)
                .put("countrycode", countryCode)
                .put("captcha", captcha)
                .put("rememberLogin", "true"),
        )
        return finishCellphoneLogin(res)
    }

    private fun finishCellphoneLogin(res: Ncm.Res): Profile? {
        session.captureSetCookie(res.setCookie)
        val json = res.json
        if (json == null) {
            lastLoginCode = 0
            lastLoginError = when {
                res.status == 0 -> "网络不可用"
                // 两条通道都回了 0 字节 —— 出口被整条拦了，不是重试能解的
                res.bodyBlank -> "网络出口被网易拦了，换个网络或改用 cookie 登录"
                else -> "服务没有回应，稍后再试"
            }
            lastLoginVerifyUrl = ""
            return null
        }
        val code = json.optInt("code", 0)
        lastLoginCode = code
        if (code != 200) {
            lastLoginError = cellphoneErrorText(res, json, code)
            // -462 是云盾人机验证，响应里带一个**参数齐全**的验证页地址，让用户在 app 内过一次
            lastLoginVerifyUrl = verifyPageUrl(json)
            return null
        }
        lastLoginError = ""
        lastLoginVerifyUrl = ""
        // 登录响应本身就带 account/profile，先落一份 —— 万一账号资料接口（weapi）
        // 也被拦，至少 uid 在，刷新时歌单 / 红心列表还能拉
        val loginProfile = json.optJSONObject("profile")
        val loginAccount = json.optJSONObject("account")
        if (loginProfile != null || loginAccount != null) {
            val seed = Profile(
                userId = loginProfile?.opt("userId")?.toString() ?: loginAccount?.opt("id")?.toString(),
                nickname = loginProfile?.optString("nickname")?.ifEmpty { null },
                avatarUrl = loginProfile?.optString("avatarUrl")?.ifEmpty { null },
                vipType = loginProfile?.optInt("vipType", 0) ?: 0,
            )
            if (!seed.userId.isNullOrEmpty()) session.setProfile(seed)
        }
        // 有些响应把 cookie 塞在 body 的 cookie 字段里，补一层兜底
        val bodyCookie = json.optString("cookie").orEmpty()
        if (bodyCookie.isNotEmpty()) session.setFromCookieString(bodyCookie)
        if (!session.isLoggedIn()) {
            lastLoginError = "没拿到登录态 cookie"
            return null
        }
        // 资料接口被拦时用登录响应里落的那份；连它都没有才给空资料，不把整次登录判死
        return refreshAccount() ?: session.getProfile() ?: Profile()
    }

    /**
     * 把登录失败的原因翻成人话。
     *
     * 网易风控那几种失败（消息体是「网络环境异常」/「操作频繁」/ code 460）极易被误读成
     * 「手机没网」，所以补一句该怎么办；其余情况优先用服务端自己的 message。
     */
    private fun cellphoneErrorText(res: Ncm.Res, json: JSONObject?, code: Int): String {
        val raw = json?.optString("message")?.ifEmpty { null }
            ?: json?.optString("msg")?.ifEmpty { null }
        val text = raw.orEmpty()

        if (res.status == 0) return "网络不可用"
        // -462：云盾挡下来了，得去浏览器过一次验证，光重试没用
        if (code == -462) return "需要先过一次网易的安全验证"
        if (text.contains("网络环境异常") || code == 460) {
            return "被网易风控拦了，换验证码登录或换个网络再试"
        }
        if (text.contains("频繁") || text.contains("过快")) return "操作太频繁，等一会儿再试"
        if (text.contains("验证码") && text.contains("错")) return "验证码不对"
        if (text.contains("密码")) return "账号或密码不对"
        if (text.isNotEmpty()) return text
        return "登录失败 (${if (code != 0) code else res.status})"
    }

    /**
     * 最近一次登录失败的原始响应，供 UI 展示「密码错误 / 需要人机验证」之类的原因。
     * [loginWithPassword] / [loginWithCaptcha] 失败时会写入。
     */
    var lastLoginError: String = ""

    /**
     * 最近一次登录 / 发验证码响应里的业务 `code`。
     *
     * 单独暴露出来是因为 `-462`（云盾人机验证）和 `460`（网络环境异常）要区别对待：
     * 前者必须过验证页，后者等一会儿就行。UI 靠这个决定冷却多久、给什么出口。
     */
    var lastLoginCode: Int = 0

    /**
     * 云盾人机验证页（code = -462 时服务端下发），非空说明要用户去浏览器过一次验证。
     * 成功登录后会清空。
     */
    var lastLoginVerifyUrl: String = ""

    fun qrUnikey(): String? {
        val json = ncm("/api/login/qrcode/unikey", JSONObject().put("type", 3)).json ?: return null
        val key = json.optString("unikey").ifEmpty { json.optString("key") }
            .ifEmpty { json.optJSONObject("data")?.optString("unikey").orEmpty() }
            .ifEmpty { json.optJSONObject("data")?.optString("key").orEmpty() }
        return key.ifEmpty { null }
    }

    fun qrStatus(key: String): Int {
        val res = ncm("/api/login/qrcode/client/login", JSONObject().put("key", key).put("type", 3))
        val json = res.json
        val code = json?.optInt("code", res.status) ?: res.status
        if (code == 803) {
            session.captureSetCookie(res.setCookie)
            // 部分响应把整串 cookie 也放进了 body.cookie，两头都收
            val bodyCookie = json?.optString("cookie").orEmpty()
            if (bodyCookie.isNotEmpty()) runCatching { session.setFromCookieString(bodyCookie) }
        }
        // 云盾挡下来时把验证页地址交出去，调用方才知道该停轮询、给用户出口
        if (code == -462) lastLoginVerifyUrl = verifyPageUrl(json)
        return code
    }

    fun refreshAccount(): Profile? {
        val json = ncm("/api/nuser/account/get", JSONObject(), "weapi").json
        val profile = json?.optJSONObject("profile")
        val account = json?.optJSONObject("account")
        val info = Profile(
            userId = profile?.opt("userId")?.toString() ?: account?.opt("id")?.toString(),
            nickname = profile?.optString("nickname")?.ifEmpty { null },
            avatarUrl = profile?.optString("avatarUrl")?.ifEmpty { null },
            vipType = profile?.optInt("vipType", account?.optInt("vipType", 0) ?: 0) ?: 0,
        )
        if (!info.userId.isNullOrEmpty() || !info.nickname.isNullOrEmpty()) session.setProfile(info)
        return session.getProfile()
    }

    fun loginWithCookieString(cookieStr: String): Profile? {
        session.setFromCookieString(cookieStr)
        return refreshAccount()
    }

    // ------------------------------------------------------------------ 收藏 / 歌单操作

    fun likeSong(songId: String, like: Boolean = true): Boolean {
        val sId = songId.trim()
        if (sId.isEmpty()) return false

        // 主路：参考项目 module/like.js —— weapi /api/radio/like
        val main = ncm(
            "/api/radio/like",
            JSONObject()
                .put("alg", "itembased")
                .put("trackId", sId)
                .put("like", like)
                .put("time", "3"),
            "weapi",
        )
        if (main.json?.optInt("code", main.status) == 200) return true

        // 兜底：官方安卓客户端同源的 eapi /api/song/like（64 位 id 不报 524）
        val e = ncm(
            "/api/song/like",
            JSONObject()
                .put("trackId", sId)
                .put("like", if (like) "true" else "false")
                .put("time", "3")
                .put("checkToken", ""),
            "eapi",
        )
        return e.json?.optInt("code", e.status) == 200
    }

    fun manipulatePlaylistTracks(playlistId: String, songId: String, add: Boolean): Boolean {
        val pidNum = playlistId.toLongOrNull() ?: return false
        val op = if (add) "add" else "del"
        fun call(trackIds: String): Int =
            ncm(
                "/api/playlist/manipulate/tracks",
                JSONObject()
                    .put("op", op)
                    .put("pid", pidNum)
                    .put("trackIds", trackIds)
                    .put("imme", "true"),
            ).let { it.json?.optInt("code", it.status) ?: it.status }

        // trackIds 是 JSON 数组串（参考项目把 tracks.split(',') 再 stringify）
        var code = call("[\"$songId\"]")
        if (code == 512) {
            // 参考项目对 512（重复添加）重试一次双份 trackIds
            code = call("[\"$songId\",\"$songId\"]")
        }
        return code == 200
    }

    fun likeList(uid: String): List<String> {
        val res = ncm("/api/song/like/get", JSONObject().put("uid", uid.toLongOrNull() ?: 0))
        val arr = res.json?.optJSONArray("ids")
        val out = mutableListOf<String>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                out.add(arr.opt(i).toString())
            }
        }
        return out
    }

    // ------------------------------------------------------------------ 一起听

    private fun normalizeRoom(json: JSONObject?): RoomInfo? {
        if (json == null) return null
        val data = json.optJSONObject("data") ?: json
        val room = data.optJSONObject("roomInfo") ?: data.optJSONObject("room") ?: data

        val roomId: String? = room.opt("roomId")?.toString()?.takeIf { it != "0" && it.isNotEmpty() }
            ?: data.opt("roomId")?.toString()?.takeIf { it != "0" && it.isNotEmpty() }
        if (roomId == null) return null

        val usersArr = room.optJSONArray("roomUsers")
            ?: room.optJSONArray("userList")
            ?: data.optJSONArray("roomUsers")
        val users = mutableListOf<RoomUser>()
        if (usersArr != null) {
            for (i in 0 until usersArr.length()) {
                val u = usersArr.optJSONObject(i) ?: continue
                val id: String? = u.opt("userId")?.toString()?.takeIf { it != "0" && it.isNotEmpty() }
                    ?: u.opt("id")?.toString()?.takeIf { it != "0" && it.isNotEmpty() }
                if (id == null) continue
                users.add(
                    RoomUser(
                        userId = id,
                        nickname = u.optString("nickname").ifEmpty { u.optString("name") },
                        avatarUrl = u.optString("avatarUrl").ifEmpty { u.optString("avatar") },
                    ),
                )
            }
        }

        val owner: String? = room.opt("creatorId")?.toString()?.takeIf { it != "0" && it.isNotEmpty() }
            ?: room.opt("ownerId")?.toString()?.takeIf { it != "0" && it.isNotEmpty() }
            ?: data.opt("creatorId")?.toString()?.takeIf { it != "0" && it.isNotEmpty() }
        val songId: String? = room.opt("songId")?.toString()?.takeIf { it != "0" && it.isNotEmpty() }
            ?: data.opt("songId")?.toString()?.takeIf { it != "0" && it.isNotEmpty() }
        val playStatus: String? = room.optString("playStatus").ifEmpty { data.optString("playStatus") }
            .takeIf { it.isNotEmpty() }
        val progress: Long = room.optLong("progress", data.optLong("progress", 0L))

        return RoomInfo(
            roomId = roomId,
            ownerId = owner,
            users = users,
            songId = songId,
            playStatus = playStatus,
            progressMs = progress,
        )
    }

    /**
     * 一起听接口的失败原因。
     *
     * 服务端的错误码在公开资料里没有完整文档，这里**不猜**具体的数字映射 ——
     * 各个端点的 code 用法并不一致（有的用 200 表成功、有的拿 code 当业务码）。
     * 靠谱的做法是两条腿走路：
     *
     * 1. 优先读响应里自带的文案字段（`message` / `msg` / `error`）。
     * 2. 没有文案时按 code 分档，再不行给一句笼统的「稍后再试」，绝不把裸数字丢给用户。
     */
    private fun ltErrorText(res: Ncm.Res, json: JSONObject?): String {
        if (json == null) {
            return if (res.status == 0) "网络不可用，检查下连接再试" else "网络不太顺，检查下连接再试"
        }
        val code: Int = json.optInt("code", res.status)

        // 服务端自己给的说明最可信，直接用它
        val raw: String = listOf("message", "msg", "error", "errMsg")
            .firstNotNullOfOrNull { key ->
                json.optString(key)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            }.orEmpty()
        if (raw.isNotEmpty()) return translateLtMessage(raw, code)

        if (res.status in 500..599) return "网易云服务器忙，稍后再试"
        if (res.status != 200) return "网络不太顺，检查下连接再试"
        return when (code) {
            200 -> ""
            301, 302 -> "登录已过期，重新登录一下"
            250, 400, 403, 405 -> "操作太频繁了，等一下再试"
            404, 4050 -> "房间不存在或已解散"
            502, 503, 504 -> "网易云服务器忙，稍后再试"
            else -> "没能完成，稍后再试"
        }
    }

    /**
     * 把服务端回的中文说明再翻译一层。
     *
     * 原始文案经常带术语（「登录态异常」「触发风控」「房间已满员，无法加入」），
     * 这里归一到用户能直接理解的说法。没匹配到就原样返回 —— 服务端的文案
     * 本身通常比我们的通用兜底更准确。
     */
    private fun translateLtMessage(raw: String, code: Int): String = when {
        raw.contains("登录") || raw.contains("未登录") -> "登录已过期，重新登录一下"
        raw.contains("满") -> "房间满员了，进不去"
        raw.contains("不存在") || raw.contains("解散") || raw.contains("过期") -> "房间不存在或已解散"
        raw.contains("风控") || raw.contains("频繁") || raw.contains("操作过快") -> "操作太频繁了，等一下再试"
        raw.contains("权限") || raw.contains("禁止") -> "当前账号没有一起听的权限"
        raw.contains("会员") || raw.contains("VIP") -> "这个功能需要网易云会员"
        raw.contains("版本") -> "网易云客户端版本太低，升级后再试"
        else -> if (code in 500..599) "网易云服务器忙，稍后再试" else raw
    }

    // 下面这些接口一律返回 `Result<T>`：成功时把数据带出来，失败时带上
    // **翻译过的人话**。以前统一返回 null，调用方只能自己编一句
    // 「创建房间失败」—— 房间满员、登录过期、被风控全都长得一模一样。
    //
    // 端点与参数按参考项目 module/listentogether_*.js 对齐；
    // 除 status 走 weapi 外全部走默认 eapi 通道，响应是网易原始 JSON。

    /** 创建房间。 */
    fun ltCreateRoom(): Result<RoomInfo> {
        val res = ncm(
            "/api/listen/together/room/create",
            JSONObject().put("refer", "songplay_more"),
        )
        if (res.code != 200) {
            return Result.failure(IllegalStateException(ltErrorText(res, res.json)))
        }
        val room: RoomInfo? = normalizeRoom(res.json)
        if (room == null) return Result.failure(IllegalStateException("房间信息没拿到，稍后再试"))
        return Result.success(room)
    }

    /** 查房间。 */
    fun ltRoomCheck(roomId: String): Result<RoomInfo> {
        val res = ncm("/api/listen/together/room/check", JSONObject().put("roomId", roomId))
        if (res.code != 200) {
            return Result.failure(IllegalStateException(ltErrorText(res, res.json)))
        }
        val room: RoomInfo? = normalizeRoom(res.json)
        if (room != null && room.roomId.isNotEmpty()) return Result.success(room)
        // 有些版本 check 只返回成员列表，roomId 得自己补回去
        return Result.success(RoomInfo(roomId = roomId, users = room?.users ?: emptyList()))
    }

    /**
     * 当前账号所在的房间（服务端按 cookie 判断）。
     *
     * 返回 `Result.success(null)` 表示**确实不在任何房间**（正常状态，不是错误），
     * 调用方据此静默清掉本地记录即可；`failure` 才是真的出问题了。
     */
    fun ltStatus(): Result<RoomInfo?> {
        val res = ncm("/api/listen/together/status/get", JSONObject(), "weapi")
        if (res.code != 200) {
            return Result.failure(IllegalStateException(ltErrorText(res, res.json)))
        }
        val room: RoomInfo? = normalizeRoom(res.json)
        if (room == null || room.roomId.isEmpty()) return Result.success(null)
        return Result.success(room)
    }

    /** 解散 / 退出房间。服务端即使返回非 200 也视为已退出，避免用户卡在房间里。 */
    fun ltEndRoom(roomId: String): Boolean {
        val res = ncm("/api/listen/together/end/v2", JSONObject().put("roomId", roomId))
        return res.code == 200
    }

    /**
     * 上报房间播放列表。
     *
     * 协议里 `playlistParam` 是一个**字符串化的 JSON**，不是嵌套对象 ——
     * 直接 put(JSONObject) 会被序列化成对象，服务端解析失败。
     * 结构按参考项目 module/listentogether_sync_list_command.js 对齐
     * （anchorSongId 空串、anchorPosition -1 由参考实现定死）。
     */
    fun ltSyncPlaylist(roomId: String, userId: String, version: Long, trackIds: List<String>): Boolean {
        val versionEntry = JSONObject()
            .put("userId", userId)
            .put("version", version)
        val versionArr = JSONArray().put(versionEntry)

        val list = JSONArray()
        for (id in trackIds) list.put(id)

        val param = JSONObject()
            .put("commandType", "REPLACE")
            .put("version", versionArr)
            .put("anchorSongId", "")
            .put("anchorPosition", -1)
            .put("randomList", list)
            .put("displayList", list)

        val res = ncm(
            "/api/listen/together/sync/list/command/report",
            JSONObject()
                .put("roomId", roomId)
                .put("playlistParam", param.toString()),
        )
        return res.code == 200
    }

    /** 取房间当前播放列表的歌曲 id。 */
    fun ltRoomPlaylist(roomId: String): List<String> {
        val res = ncm("/api/listen/together/sync/playlist/get", JSONObject().put("roomId", roomId))
        if (res.code != 200) return emptyList()

        val data = res.json?.optJSONObject("data") ?: res.json
        var arr = data?.optJSONArray("displayList")
        if (arr == null || arr.length() == 0) arr = data?.optJSONArray("randomList")
        if (arr == null || arr.length() == 0) arr = data?.optJSONArray("playlist")
        val out = mutableListOf<String>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.opt(i)
                val id: String? = when (item) {
                    is JSONObject -> item.opt("id")?.toString() ?: item.opt("songId")?.toString()
                    null -> null
                    else -> item.toString()
                }
                if (!id.isNullOrEmpty() && id != "0") out.add(id)
            }
        }
        return out
    }

    /**
     * 上报播放指令。
     *
     * [commandType] 取值 `PLAY` / `PAUSE` / `GOTO` / `seek`。
     * `commandInfo` 同样是字符串化 JSON（按参考项目 play/command 模块的结构）。
     */
    fun ltPlayCommand(
        roomId: String,
        commandType: String,
        progressMs: Long,
        playStatus: String,
        formerSongId: String,
        targetSongId: String,
        clientSeq: Long,
    ): Boolean {
        val info = JSONObject()
            .put("commandType", commandType)
            .put("progress", progressMs)
            .put("playStatus", playStatus)
            .put("formerSongId", formerSongId)
            .put("targetSongId", targetSongId)
            .put("clientSeq", clientSeq)

        val res = ncm(
            "/api/listen/together/play/command/report",
            JSONObject()
                .put("roomId", roomId)
                .put("commandInfo", info.toString()),
        )
        return res.code == 200
    }

    /**
     * 心跳。响应里带回房间的当前状态，是这套协议里唯一的「读数」手段。
     * 返回 null 表示心跳失败（房间已散 / 网络异常）。
     */
    fun ltHeartbeat(roomId: String, songId: String, playStatus: String, progressMs: Long): RoomInfo? {
        val res = ncm(
            "/api/listen/together/heartbeat",
            JSONObject()
                .put("roomId", roomId)
                .put("songId", songId)
                .put("playStatus", playStatus)
                .put("progress", progressMs),
        )
        if (res.code != 200) return null

        val room = normalizeRoom(res.json)
        if (room != null) return room

        // 心跳响应常常只有播放信息、没有 roomId，自己补
        val data = res.json?.optJSONObject("data") ?: res.json
        val sid: String? = data?.opt("songId")?.toString()?.takeIf { it != "0" && it.isNotEmpty() }
        val ps: String? = data?.optString("playStatus")?.takeIf { it.isNotEmpty() }
        return RoomInfo(
            roomId = roomId,
            songId = sid,
            playStatus = ps,
            progressMs = data?.optLong("progress", 0L) ?: 0L,
        )
    }

    companion object {
        fun defaultHttp(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

        /**
         * 封面 / 音频直链的 OkHttpClient：网易 CDN 认 UA + Referer，
         * 带上会话 cookie 没坏处（和旧版一致）。
         */
        fun httpWithSession(session: SessionStore): OkHttpClient =
            defaultHttp().newBuilder()
                .addInterceptor { chain ->
                    val cookies = Crypto.serializeCookies(Crypto.defaultNeteaseCookies(session.getCookies()))
                    val req = chain.request().newBuilder()
                        .header("Cookie", cookies)
                        .header("Referer", "https://music.163.com/")
                        .header("Origin", "https://music.163.com")
                        .build()
                    chain.proceed(req)
                }
                .build()

        private val tagRe = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")
        private val creditRe = Regex("^(作词|作曲|编曲|制作人|混音|录音|和声|出品|填词|Vocal|Lyrics|Composer)[:：\\s]")

        fun parseLrc(raw: String): List<LyricLine> {
            val out = mutableListOf<LyricLine>()
            for (line in raw.split('\n')) {
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("{")) continue
                val tags = tagRe.findAll(t).toList()
                if (tags.isEmpty()) continue
                val text = t.substring(tags.last().range.last + 1).trim()
                if (text.isEmpty() || creditRe.containsMatchIn(text)) continue
                for (m in tags) {
                    val min = m.groupValues[1].toLong()
                    val sec = m.groupValues[2].toLong()
                    val frac = m.groupValues[3]
                    val ms = when {
                        frac.isEmpty() -> 0L
                        frac.length == 1 -> frac.toLong() * 100
                        frac.length == 2 -> frac.toLong() * 10
                        else -> frac.take(3).toLong()
                    }
                    out.add(LyricLine(timeMs = min * 60_000 + sec * 1000 + ms, text = text))
                }
            }
            return out.sortedBy { it.timeMs }
        }
    }
}
