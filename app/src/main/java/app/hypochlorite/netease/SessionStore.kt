package app.hypochlorite.netease

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("netease-session", Context.MODE_PRIVATE)

    /**
     * 播放状态单独一个 prefs 文件。
     *
     * 放同一个文件里会出事：它存的是整条队列（上千首就是几百 KB），而 SharedPreferences
     * 是**整份文件**读进内存的 —— 塞在这里会让每次启动解析登录 prefs 都要顺带啃几百 KB，
     * 任何一次 edit 也都要重写整份文件。分开之后登录 prefs 始终很小。
     */
    private val playbackPrefs =
        context.getSharedPreferences("playback-state", Context.MODE_PRIVATE)

    private var cookies: MutableMap<String, String> = linkedMapOf()
    private var profile: Profile? = null
    private var updatedAt: String? = null

    init {
        load()
        // 迁移：旧版本把播放队列存在 session prefs 的 playback_state 里，把它清掉，
        // 让已经装了旧版的设备也能把那个大文件缩回去。
        runCatching {
            if (prefs.contains("playback_state")) prefs.edit().remove("playback_state").apply()
        }
    }

    @Synchronized
    fun load() {
        val raw = prefs.getString("json", null)
        if (raw.isNullOrBlank()) {
            cookies = linkedMapOf()
            profile = null
            updatedAt = null
            return
        }
        try {
            val json = JSONObject(raw)
            cookies = linkedMapOf()
            val c = json.optJSONObject("cookies")
            if (c != null) {
                val keys = c.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    cookies[k] = c.optString(k, "")
                }
            }
            profile = json.optJSONObject("profile")?.let { p ->
                Profile(
                    userId = p.optString("userId").ifEmpty { null },
                    nickname = p.optString("nickname").ifEmpty { null },
                    avatarUrl = p.optString("avatarUrl").ifEmpty { null },
                    vipType = p.optInt("vipType", 0),
                )
            }
            updatedAt = json.optString("updatedAt").ifEmpty { null }
        } catch (_: Exception) {
            cookies = linkedMapOf()
            profile = null
            updatedAt = null
        }
    }

    @Synchronized
    fun save() {
        val c = JSONObject()
        for ((k, v) in cookies) c.put(k, v)
        val root = JSONObject()
            .put("cookies", c)
            .put("updatedAt", updatedAt)
        profile?.let {
            root.put(
                "profile",
                JSONObject()
                    .put("userId", it.userId)
                    .put("nickname", it.nickname)
                    .put("avatarUrl", it.avatarUrl)
                    .put("vipType", it.vipType),
            )
        }
        prefs.edit().putString("json", root.toString()).apply()
    }

    @Synchronized
    fun getCookies(): Map<String, String> = deviceIdentity() + cookies

    /**
     * 设备指纹，**以 cookie 的形态存在，但和登录会话分开落盘**。
     *
     * 对应参考仓库 neteasecloudmusicapienhanced/api-enhanced `util/request.js` 里的
     * `processCookieObject` —— 它对每个请求都会塞进这几个键：
     *
     * ```
     * deviceId     52 位十六进制（大写），一台设备一个，永不变
     * _ntes_nuid   32 位随机小写十六进制
     * _ntes_nnid   "${_ntes_nuid},${生成时刻毫秒}"
     * WNMCID       6 位小写字母 + "." + 毫秒 + ".01.0"
     * WEVNSM       固定 "1.0.0"
     * ```
     *
     * **为什么必须落盘、必须稳定**：云盾的核心判据就是「同一个 deviceId 的历史行为」。
     * 之前我们一个都不带，等于每次请求都是「一台刚出生的设备」—— 每次登录都被当成首次
     * 陌生设备，风控分只能一路涨。反过来，同一个 deviceId 只要正常用过几次，权重就会
     * 慢慢下来。
     *
     * **为什么不跟会话一起清**：退出登录时 [clear] 会清空 `cookies`，但设备指纹不能跟着
     * 变 —— 换 deviceId 在风控眼里就是「换了一台设备」，等于自毁积累。所以它存在独立的
     * prefs 键上，[clear] 不碰。
     */
    @Synchronized
    fun deviceIdentity(): Map<String, String> {
        fun read(key: String): String =
            runCatching { prefs.getString(key, "") ?: "" }.getOrDefault("")

        var deviceId = read("dev_device_id")
        var nuid = read("dev_ntes_nuid")
        var nnid = read("dev_ntes_nnid")
        var wnmcid = read("dev_wnmcid")

        if (deviceId.isEmpty() || nuid.isEmpty()) {
            if (deviceId.isEmpty()) deviceId = randomHex(52, upper = true)
            if (nuid.isEmpty()) nuid = randomHex(32)
            if (nnid.isEmpty()) nnid = "$nuid,${System.currentTimeMillis()}"
            if (wnmcid.isEmpty()) {
                val letters = "abcdefghijklmnopqrstuvwxyz"
                val head = buildString(6) { repeat(6) { append(letters[kotlin.random.Random.nextInt(letters.length)]) } }
                wnmcid = "$head.${System.currentTimeMillis()}.01.0"
            }
            // 整块一次性写入：绝不能让 deviceId 和 nuid 出现「一个存了一个没存」的中间态，
            // 那会生成出永远对不上的 _ntes_nnid。
            runCatching {
                prefs.edit()
                    .putString("dev_device_id", deviceId)
                    .putString("dev_ntes_nuid", nuid)
                    .putString("dev_ntes_nnid", nnid)
                    .putString("dev_wnmcid", wnmcid)
                    .apply()
            }
        }

        return linkedMapOf(
            "deviceId" to deviceId,
            "_ntes_nuid" to nuid,
            "_ntes_nnid" to nnid,
            "WNMCID" to wnmcid,
            "WEVNSM" to "1.0.0",
            "__remember_me" to "true",
            "ntes_kaola_ad" to "1",
        )
    }

    private fun randomHex(length: Int, upper: Boolean = false): String {
        val pool = if (upper) "0123456789ABCDEF" else "0123456789abcdef"
        return buildString(length) {
            repeat(length) { append(pool[kotlin.random.Random.nextInt(pool.length)]) }
        }
    }

    @Synchronized
    fun setCookies(next: Map<String, String>, merge: Boolean = true): Map<String, String> {
        cookies = if (merge) (cookies + next).toMutableMap() else next.toMutableMap()
        updatedAt = java.time.Instant.now().toString()
        save()
        return getCookies()
    }

    @Synchronized
    fun captureSetCookie(setCookie: List<String>?): Map<String, String> {
        val parsed = Crypto.parseSetCookie(setCookie)
        if (parsed.isEmpty()) return getCookies()
        return setCookies(parsed, true)
    }

    @Synchronized
    fun setFromCookieString(cookieStr: String): Map<String, String> {
        val parsed = Crypto.parseCookieString(cookieStr).toMutableMap()
        if (parsed["MUSIC_U"].isNullOrEmpty() && parsed["MUSIC_A"].isNullOrEmpty() && parsed.isEmpty()) {
            throw IllegalArgumentException("cookie string contained no session keys")
        }
        if (!parsed.containsKey("__csrf") && !parsed.containsKey("csrf")) {
            parsed["__csrf"] = java.util.UUID.randomUUID().toString().replace("-", "")
        }
        return setCookies(parsed, true)
    }

    @Synchronized
    fun setProfile(p: Profile?): Profile? {
        profile = p
        updatedAt = java.time.Instant.now().toString()
        save()
        return profile
    }

    @Synchronized
    fun clear() {
        cookies = linkedMapOf()
        profile = null
        updatedAt = java.time.Instant.now().toString()
        save()
        // 退出登录后房间凭证一定会失效，顺手清掉，免得下次登录后自动续进别人的房间
        saveListenRoom(null, false)
    }

    @Synchronized
    fun isLoggedIn(): Boolean = !cookies["MUSIC_U"].isNullOrEmpty() || !cookies["MUSIC_A"].isNullOrEmpty()

    /**
     * 能否使用「一起听」。
     *
     * 比 [isLoggedIn] 严格：房间接口全都需要真实 uid（`sync/list/command` 里的 version
     * 是按 userId 索引的），而且只有 `MUSIC_U` 这条路能签发这些 eapi 请求 ——
     * 仅凭 `MUSIC_A`（游客态）能查歌、但拿不到房间。所以两个条件都要满足。
     */
    @Synchronized
    fun canUseListenTogether(): Boolean =
        !cookies["MUSIC_U"].isNullOrEmpty() && !profile?.userId.isNullOrEmpty()

    @Synchronized
    fun getCsrf(): String {
        var c = cookies["__csrf"] ?: cookies["csrf"]
        if (c.isNullOrEmpty()) {
            c = java.util.UUID.randomUUID().toString().replace("-", "")
            cookies["__csrf"] = c
            save()
        }
        return c
    }

    /**
     * 丢掉一个 cookie。只用于**票据类**的键（目前只有 `NMTID`）。
     *
     * 云盾对过期 / 伪造的 NMTID 反应比不带还激烈（实测直接 `-462`），所以刷新拿不到
     * 新票据时宁可把旧的摘掉。
     */
    @Synchronized
    fun dropCookie(name: String) {
        if (cookies.remove(name) != null) save()
    }

    /**
     * 上次成功跑通的登录通道（`plain` / `weapi`）。
     *
     * 登录只走这一条，不再两条串行试 —— 多试一条就是多一次失败尝试，风控分数是
     * 按「同一设备连续失败登录」累加的。
     */
    fun getLoginChannel(): String =
        runCatching { prefs.getString("login_channel", "") ?: "" }.getOrDefault("")

    fun saveLoginChannel(channel: String) {
        runCatching { prefs.edit().putString("login_channel", channel).apply() }
    }

    @Synchronized
    fun getProfile(): Profile? = profile

    fun getLikedSongIds(): Set<String> {
        return prefs.getStringSet("liked_song_ids", emptySet()) ?: emptySet()
    }

    fun saveLikedSongIds(ids: Set<String>) {
        prefs.edit().putStringSet("liked_song_ids", ids).apply()
    }

    // 封面取色主题（ui 层用，放这里只是为了跟着同一个 prefs）
    // 读取统一包 runCatching：prefs 里某个 key 的类型万一被写成了别的，
    // getBoolean/getInt 会抛 ClassCastException，而这几行跑在启动路径上 —— 一旦抛就是「打不开」。
    // 这些全是可再生的缓存，读不到就直接回退默认值，绝不能拖垮启动。

    fun getThemeMode(): String =
        runCatching { prefs.getString("theme_mode", "system") ?: "system" }.getOrDefault("system")

    fun saveThemeMode(mode: String) {
        prefs.edit().putString("theme_mode", mode).apply()
    }

    fun isMonetEnabled(): Boolean =
        runCatching { prefs.getBoolean("monet_enabled", true) }.getOrDefault(true)

    fun saveMonetEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("monet_enabled", enabled).apply()
    }

    fun isRevealEnabled(): Boolean =
        runCatching { prefs.getBoolean("reveal_enabled", true) }.getOrDefault(true)

    fun saveRevealEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("reveal_enabled", enabled).apply()
    }

    /** 上次的封面 seed，冷启动直接还原配色；0 / 读不到都表示没有 */
    fun getMonetSeed(): Int? =
        runCatching { prefs.getInt("monet_seed", 0) }.getOrNull()?.takeIf { it != 0 }

    fun saveMonetSeed(seed: Int) {
        prefs.edit().putInt("monet_seed", seed).apply()
    }

    // 一起听：只存「上次在哪个房间」，不存房间快照 ——
    // 房间成员会变、歌会变，缓存下来只会在冷启动时显示一堆过期信息。
    // 续房一律靠 ltStatus 回查（见 ListenTogether.restore）。

    fun getListenRoomId(): String? =
        runCatching { prefs.getString("listen_room_id", null) }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun saveListenRoom(roomId: String?, hosting: Boolean, inviterId: String? = null) {
        runCatching {
            prefs.edit().apply {
                if (roomId.isNullOrEmpty()) {
                    remove("listen_room_id")
                    remove("listen_inviter_id")
                } else {
                    putString("listen_room_id", roomId)
                    if (!inviterId.isNullOrEmpty()) putString("listen_inviter_id", inviterId)
                }
                putBoolean("listen_room_hosting", hosting)
            }.apply()
        }
    }

    fun isListenHosting(): Boolean =
        runCatching { prefs.getBoolean("listen_room_hosting", false) }.getOrDefault(false)

    fun getListenInviterId(): String? =
        runCatching { prefs.getString("listen_inviter_id", null) }.getOrNull()?.takeIf { it.isNotEmpty() }

    // 音质偏好。
    // 之前这一项只活在 `PlayerSnapshot` 里 —— 进程一没就回到默认的「高」，
    // 用户每次重开都要重选一遍。这里补上落盘，读回时统一交给 `Quality.resolve`
    // 校验：prefs 里可能留着已经下线的档位（比如老版本的 "jymaster"），
    // 也可能被写成了别的类型，两者都由 resolve 兜到默认档。
    fun getQualityId(): String? =
        runCatching { prefs.getString("quality_id", null) }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun saveQualityId(id: String) {
        runCatching { prefs.edit().putString("quality_id", id).apply() }
    }

    // ------------------------------------------------------------------ HiFi 音频输出
    // 读写一律包 runCatching：这些 key 都在启动路径上被读，prefs 里类型一旦不符
    // （getBoolean/getInt 抛 ClassCastException）就会变成「打不开」。
    // 全部是可再生的本地偏好，读不到直接回默认值。

    /** USB 独占输出：让本机直接占用 USB 音频设备，绕开系统的混音与重采样 */
    fun isUsbExclusive(): Boolean =
        runCatching { prefs.getBoolean("hifi_usb_exclusive", false) }.getOrDefault(false)

    fun saveUsbExclusive(on: Boolean) {
        runCatching { prefs.edit().putBoolean("hifi_usb_exclusive", on).apply() }
    }

    /** 独占音频焦点：独奏时不与系统其它声音同时发声 */
    fun isExclusiveFocus(): Boolean =
        runCatching { prefs.getBoolean("hifi_exclusive_focus", false) }.getOrDefault(false)

    fun saveExclusiveFocus(on: Boolean) {
        runCatching { prefs.edit().putBoolean("hifi_exclusive_focus", on).apply() }
    }

    /** 保持唤醒：锁屏继续出声、同时压住 CPU 降频带来的爆音 */
    fun isKeepAwake(): Boolean =
        runCatching { prefs.getBoolean("hifi_keep_awake", true) }.getOrDefault(true)

    fun saveKeepAwake(on: Boolean) {
        runCatching { prefs.edit().putBoolean("hifi_keep_awake", on).apply() }
    }

    /** 系统级输出音量直控：绕开 app 侧数字衰减 */
    fun isDirectVolume(): Boolean =
        runCatching { prefs.getBoolean("hifi_direct_volume", false) }.getOrDefault(false)

    fun saveDirectVolume(on: Boolean) {
        runCatching { prefs.edit().putBoolean("hifi_direct_volume", on).apply() }
    }

    /**
     * 精确音量，0..100（app 侧数字衰减）。
     *
     * 默认 93 ≈ −2dB —— 也就是每减 7 格贡献约 1dB，一格刚好落在人耳可闻的门槛上。
     * 之所以不是 100：整数音量被 DAP 和播放器普遍当作「有损」处理，留一点余量
     * 既避免数字削顶，也符合发烧圈 6~10dB headroom 的习惯。
     */
    fun getGainPercent(): Int =
        runCatching { prefs.getInt("hifi_gain", 93) }.getOrDefault(93).coerceIn(0, 100)

    fun saveGainPercent(v: Int) {
        runCatching { prefs.edit().putInt("hifi_gain", v.coerceIn(0, 100)).apply() }
    }

    /** 记住上次用过的 USB 设备，插回来能直接认出来 */
    fun getRememberedUsbDeviceId(): Int =
        runCatching { prefs.getInt("hifi_usb_device", -1) }.getOrDefault(-1)

    fun saveRememberedUsbDeviceId(id: Int) {
        runCatching { prefs.edit().putInt("hifi_usb_device", id).apply() }
    }

    fun clearMonetSeed() {
        prefs.edit().remove("monet_seed").apply()
    }

    fun clearPlaybackState() {
        runCatching { playbackPrefs.edit().remove("playback_state").apply() }
        runCatching { prefs.edit().remove("playback_state").apply() }
    }

    fun savePlaybackState(record: PlaybackStateRecord) {
        try {
            val arr = JSONArray()
            for (s in record.queue) {
                val obj = JSONObject()
                obj.put("id", s.id)
                obj.put("name", s.name)
                val arts = JSONArray()
                s.artists.forEach { arts.put(it) }
                obj.put("artists", arts)
                obj.put("album", s.album)
                obj.put("durationMs", s.durationMs)
                obj.put("cover", s.cover)
                arr.put(obj)
            }
            val root = JSONObject()
            root.put("queue", arr)
            root.put("index", record.index)
            root.put("positionMs", record.positionMs)
            root.put("roam", record.roam)
            root.put("playMode", record.playMode)
            playbackPrefs.edit().putString("playback_state", root.toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun getPlaybackState(): PlaybackStateRecord? {
        val raw = runCatching { playbackPrefs.getString("playback_state", null) }.getOrNull()
            ?: return null
        return try {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("queue") ?: return null
            val q = mutableListOf<Song>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val artsArr = obj.optJSONArray("artists")
                val arts = mutableListOf<String>()
                if (artsArr != null) {
                    for (j in 0 until artsArr.length()) {
                        arts.add(artsArr.optString(j))
                    }
                }
                q.add(
                    Song(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        artists = arts,
                        album = obj.optString("album"),
                        durationMs = obj.optLong("durationMs", 0L),
                        cover = obj.optString("cover")
                    )
                )
            }
            if (q.isEmpty()) return null
            PlaybackStateRecord(
                queue = q,
                index = root.optInt("index", 0),
                positionMs = root.optLong("positionMs", 0L),
                roam = root.optBoolean("roam", false),
                playMode = root.optString("playMode", "SEQUENCE")
            )
        } catch (_: Exception) {
            null
        }
    }
}

data class PlaybackStateRecord(
    val queue: List<Song>,
    val index: Int,
    val positionMs: Long,
    val roam: Boolean = false,
    val playMode: String = "SEQUENCE",
)
