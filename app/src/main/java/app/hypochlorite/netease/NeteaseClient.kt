package app.hypochlorite.netease

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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
 * | 一起听 lt* | /api/listen/together/ 各端点 | eapi（status 走 weapi），客户端身份是 android 9.5.95 |
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
        identity: Ncm.ClientIdentity? = null,
    ): Ncm.Res = Ncm.request(
        http,
        session,
        uri,
        data,
        crypto,
        fakeNmtid,
        plainFallback = plainFallback,
        identity = identity,
    )

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
            creatorName = creator?.optString("nickname").orEmpty(),
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

    /**
     * cloudsearch。type：1 单曲，10 专辑，100 歌手，1000 歌单。
     *
     * 到不了网易，或者返回了没有 result 的错误码，才算失败。空列表是一次成功的搜索。
     */
    private fun cloudSearch(query: String, type: Int, limit: Int, offset: Int): JSONObject {
        val res = ncm(
            "/api/cloudsearch/pc",
            JSONObject()
                .put("s", query)
                .put("type", type)
                .put("limit", limit)
                .put("offset", offset)
                .put("total", true),
        )
        val json = res.json ?: throw IOException("cloudsearch unreachable")
        if (json.optJSONObject("result") == null && json.optInt("code", 200) != 200) {
            throw IOException("cloudsearch ${json.optInt("code")}")
        }
        return json
    }

    fun searchSongs(query: String, limit: Int = 20, offset: Int = 0): List<Song> =
        searchSongPage(query, limit, offset).items

    fun searchSongPage(query: String, limit: Int = 20, offset: Int = 0): SearchPage<Song> {
        val q = query.trim()
        if (q.isEmpty()) return SearchPage(emptyList(), 0)
        // 歌曲链接 / 纯 id 只在第一页短路。翻页仍走关键词，避免下一页又吐回同一首。
        if (offset == 0) {
            SongId.parse(q)?.let { id ->
                getSongDetail(id)?.let { return SearchPage(listOf(it), 1) }
            }
        }
        val json = cloudSearch(q, type = 1, limit = limit, offset = offset)
        val result = json.optJSONObject("result")
        val songs = result?.optJSONArray("songs")
        val total = if (result != null && result.has("songCount")) result.optInt("songCount") else -1
        val out = mutableListOf<Song>()
        if (songs != null) {
            for (i in 0 until minOf(songs.length(), limit)) {
                normalizeSong(songs.optJSONObject(i))?.let { out.add(it) }
            }
        }
        if (offset == 0 && out.any { it.cover.isEmpty() }) {
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
        return SearchPage(out, total)
    }

    fun searchPlaylists(query: String, limit: Int = 20, offset: Int = 0): List<Playlist> =
        searchPlaylistPage(query, limit, offset).items

    fun searchPlaylistPage(query: String, limit: Int = 20, offset: Int = 0): SearchPage<Playlist> {
        val q = query.trim()
        if (q.isEmpty()) return SearchPage(emptyList(), 0)
        return parsePlaylistSearch(cloudSearch(q, type = 1000, limit = limit, offset = offset), limit)
    }

    fun searchAlbums(query: String, limit: Int = 20, offset: Int = 0): List<Album> =
        searchAlbumPage(query, limit, offset).items

    fun searchAlbumPage(query: String, limit: Int = 20, offset: Int = 0): SearchPage<Album> {
        val q = query.trim()
        if (q.isEmpty()) return SearchPage(emptyList(), 0)
        return parseAlbumSearch(cloudSearch(q, type = 10, limit = limit, offset = offset), limit)
    }

    fun searchArtists(query: String, limit: Int = 20, offset: Int = 0): List<Artist> =
        searchArtistPage(query, limit, offset).items

    fun searchArtistPage(query: String, limit: Int = 20, offset: Int = 0): SearchPage<Artist> {
        val q = query.trim()
        if (q.isEmpty()) return SearchPage(emptyList(), 0)
        return parseArtistSearch(cloudSearch(q, type = 100, limit = limit, offset = offset), limit)
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

    /** 打开歌手页时只要第一条。失败当成没找到，调用方再走歌名兜底。 */
    fun searchArtist(query: String): Pair<String?, String?> {
        val first = runCatching { searchArtistPage(query, limit = 1).items.firstOrNull() }.getOrNull()
        return first?.id to first?.cover?.ifEmpty { null }
    }

    /** 打开专辑页时只要第一条。失败当成没找到。 */
    fun searchAlbum(query: String): Pair<String?, String?> {
        val first = runCatching { searchAlbumPage(query, limit = 1).items.firstOrNull() }.getOrNull()
        return first?.id to first?.cover?.ifEmpty { null }
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

    // ------------------------------------------------------------------ 听歌识曲

    /**
     * 听歌识曲 —— 参考项目 `module/audio_match.js`，uri 与查询参数形状逐字对齐。
     *
     * **匿名端点**：不加密通道、不带登录态，直连 interface 域名，所以这一支不走
     * [ncm]（那三层通道都会把参数塞进表单体，这里要的是 query string）。
     *
     * [fingerprintBase64] 必须是**音频指纹**，不是 PCM：实测把 3 秒 8kHz 单声道
     * 的 s16le PCM 直接 base64 上去，服务端回 `code 400 请求解析失败`。指纹由
     * 网易那套 C++ 提取器（Emscripten 编的 wasm）算出，见 [app.hypochlorite.audio.NcmFingerprintWasm]。
     * 长度要和 [durationSeconds] 对得上，但**指纹长度随音频内容变**（实测 3 秒窗口：纯正弦 288 字节、
     * 真歌 738~786 字节），所以别按定长断言。
     *
     * 结果分级：没匹配上是 `result: null` + `noMatchReason`（实测 10），属于成功调用
     * 返回空列表；只有解析失败（400）或网络不通才抛。
     */
    fun audioMatch(fingerprintBase64: String, durationSeconds: Int): List<AudioMatchHit> {
        val url = Ncm.API_DOMAIN + "/api/music/audio/match?" + arrayOf(
            "sessionId" to Ncm.newSessionId(),
            "algorithmCode" to "shazam_v2",
            "duration" to durationSeconds.toString(),
            "rawdata" to fingerprintBase64,
            "times" to "1",
            "decrypt" to "1",
        ).joinToString("&") { (k, v) ->
            "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", Ncm.UA_API)
            .get()
            .build()
        val text = try {
            http.newCall(request).execute().use { it.body?.string().orEmpty() }
        } catch (_: Exception) {
            throw IOException("audio match unreachable")
        }
        val json = runCatching { JSONObject(text) }.getOrNull() ?: throw IOException("audio match: 响应不是 JSON")
        if (json.optInt("code", 200) != 200) throw IOException("audio match ${json.optInt("code")}")
        val arr = json.optJSONObject("data")?.optJSONArray("result") ?: return emptyList()
        val hits = mutableListOf<AudioMatchHit>()
        val thinById = linkedMapOf<String, JSONObject>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            // 这里的 song 是精简形态（name/id/artists[]/album{}），normalizeSong
            // 认 artists 复数键，能直接吃；缺时长字段就当 0。
            val songObj = item.optJSONObject("song") ?: continue
            val song = normalizeSong(songObj) ?: continue
            hits.add(AudioMatchHit(song = song, startTimeMs = item.optLong("startTime", 0)))
            thinById[song.id] = songObj
        }
        if (hits.isEmpty()) return emptyList()
        // 识曲薄对象经常不带 privilege/pc；拉一次 detail 才能把云盘私传和官网轨分开。
        // 失败就退回薄对象上的分数（仍可能靠专辑名等弱信号排序）。
        val richById = runCatching { songDetailObjects(hits.map { it.song.id }) }.getOrDefault(emptyMap())
        val byId = thinById.toMutableMap().apply { putAll(richById) }
        return preferOfficialAudioMatchHits(hits, byId)
    }

    /**
     * /api/v3/song/detail 的原始 songs[]，按 id 索引。给识曲排序用，
     * 比 [getSongDetails] 多留 privilege / pc / copyrightId。
     */
    private fun songDetailObjects(ids: List<String>): Map<String, JSONObject> {
        val idNums = ids.mapNotNull { it.toLongOrNull() }.distinct()
        if (idNums.isEmpty()) return emptyMap()
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
        val arr = res.json?.optJSONArray("songs") ?: return emptyMap()
        val privileges = res.json?.optJSONArray("privileges")
        val privilegeById = linkedMapOf<String, JSONObject>()
        if (privileges != null) {
            for (i in 0 until privileges.length()) {
                val p = privileges.optJSONObject(i) ?: continue
                val pid = p.opt("id")?.toString() ?: continue
                privilegeById[pid] = p
            }
        }
        val out = linkedMapOf<String, JSONObject>()
        for (i in 0 until arr.length()) {
            val song = arr.optJSONObject(i) ?: continue
            val id = song.opt("id")?.toString() ?: continue
            // detail 的 privilege 在并列数组里，按 id 嵌回 song 方便统一打分。
            if (song.optJSONObject("privilege") == null) {
                privilegeById[id]?.let { song.put("privilege", it) }
            }
            out[id] = song
        }
        return out
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
    //
    // 房间协议按官方客户端：创建 / 接受邀请 / 状态 / 播放列表指令 / 播放指令 / 心跳。
    // 全部带移动端身份（见 [Ncm.ClientIdentity]）。原始 JSON 交回引擎，
    // 成功与解散由 [requireListenSuccess] / [isListenClosed] 判断。

    private fun lt(uri: String, data: JSONObject = JSONObject(), crypto: String = ""): JSONObject {
        val res = ncm(uri, data, crypto, identity = Ncm.ClientIdentity.MOBILE)
        if (res.status == 488 || res.json?.optInt("code") == 488) {
            return res.json ?: JSONObject().put("code", 488)
        }
        return res.json ?: throw IllegalStateException(
            if (res.status == 0) "网络不可用，检查下连接再试" else "网络不太顺，检查下连接再试",
        )
    }

    /** 双人房。 */
    fun ltCreateRoom(): JSONObject = lt("/api/listen/together/room/create", duoRoomBody())

    /** 多人房。当前歌曲和后面的队列在建房时就种进去。 */
    fun ltCreateMultiRoom(songId: Long, playedMillis: Long, nextSongIds: List<Long>): JSONObject =
        lt("/api/listen/together/multi/room/create", multiRoomBody(songId, playedMillis, nextSongIds))

    /** 凭邀请进入房间。只拿房间号、不带邀请人，服务端不会把你加进去。 */
    fun ltAccept(roomId: String, inviterId: Long): JSONObject =
        lt("/api/listen/together/play/invitation/accept", acceptBody(roomId, inviterId))

    fun ltRoomCheck(roomId: String): JSONObject =
        lt("/api/listen/together/room/check", JSONObject().put("roomId", roomId))

    /** 当前账号还在不在某个房间里。走 weapi，和官方状态接口一致。 */
    fun ltStatus(): JSONObject = lt("/api/listen/together/status/get", JSONObject(), "weapi")

    fun ltEnd(roomId: String): JSONObject =
        lt("/api/listen/together/end/v2", JSONObject().put("roomId", roomId))

    fun ltHeartbeat(roomId: String, songId: Long, playStatus: String, progressMillis: Long): JSONObject =
        lt(
            "/api/listen/together/heartbeat",
            JSONObject()
                .put("roomId", roomId)
                .put("songId", songId.toString())
                .put("playStatus", playStatus)
                .put("progress", progressMillis.coerceAtLeast(0L).toString()),
        )

    fun ltPlayCommand(
        roomId: String,
        commandType: String,
        progressMillis: Long,
        playStatus: String,
        formerSongId: Long,
        targetSongId: Long,
        clientSeq: Long,
    ): JSONObject = lt(
        "/api/listen/together/play/command/report",
        JSONObject()
            .put("roomId", roomId)
            .put(
                "commandInfo",
                playCommandInfo(commandType, progressMillis, playStatus, formerSongId, targetSongId, clientSeq),
            ),
    )

    fun ltSyncPlaylist(
        roomId: String,
        versions: List<ListenPlaylistVersion>,
        playMode: String,
        displayIds: List<Long>,
    ): JSONObject = lt(
        "/api/listen/together/sync/list/command/report",
        JSONObject()
            .put("roomId", roomId)
            .put("playlistParam", playlistParam("REPLACE", versions, playMode, displayIds)),
    )

    fun ltPlaylist(roomId: String): JSONObject =
        lt("/api/listen/together/sync/playlist/get", JSONObject().put("roomId", roomId))

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
