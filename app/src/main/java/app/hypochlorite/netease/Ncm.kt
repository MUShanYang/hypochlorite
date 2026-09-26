package app.hypochlorite.netease

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 网易云请求通道 —— [api-enhanced](https://github.com/neteasecloudmusicapienhanced/api-enhanced)
 * `util/request.js` 的 **Kotlin 内建移植**（2026-09-19 起 app 不再连任何外部代理服务，
 * 请求构造逻辑全部在本项目内）。
 *
 * 移植范围与忠实度：
 * - **三种加密通道**：`weapi`（AES 双层 + RSA，发 music.163.com/weapi/ 下各端点）、
 *   `eapi`（AES-ECB 摘要，发 interfacepc.music.163.com/eapi/ 下各端点）、
 *   `api`（明文表单，发 interface.music.163.com/api/ 下各端点）。
 *   `xeapi`（x25519 会话握手 + 服务端公钥）没有移植 —— 它只被「注册游客票据」和
 *   `/song/url/v1` 的参考实现用到，前者用不上、后者走 eapi（旧版直连已验证可用）。
 * - **默认通道 = eapi**（对应参考项目 `APP_CONF.encrypt = true`）：模块没显式给
 *   加密方式时一律 eapi。
 * - **全部 POST + form 表单**（参考项目 axios `URLSearchParams` 的等价物）。
 * - **cookie 处理**（`processCookieObject`）：设备指纹（deviceId/_ntes_nuid/_ntes_nnid/
 *   WNMCID/WEVNSM）由 [SessionStore.deviceIdentity] 落盘提供；os 三元组取
 *   `osMap.pc`；**NMTID 缓过了真实值就用，没有时非 eapi 请求按参考实现伪造一条
 *   `'00O'+19 位 hex`（不落盘），eapi 请求不带 —— 让服务端下发，Set-Cookie 会
 *   被 [SessionStore.captureSetCookie] 收进会话**。
 * - **eapi 的 Cookie 头是「压平的 header 对象」**（osver/deviceId/MUSIC_U/...
 *   逐个 encodeURIComponent），同时同一个 header 对象会作为 `data.header`
 *   进加密载荷 —— 这是 eapi 的双份特征，少一份都过不了签名校验。
 * - **MUSIC_A（游客票据）**：只在会话里已有（cookie 登录带进来）时使用；
 *   参考项目靠部署时跑一次 xeapi 注册来领，app 内不自动注册（见上）。
 *
 * 与参考实现的两处**有意偏离**（都有实测依据，别「顺手改回去」）：
 * 1. 登录 / 发验证码请求在无真实 NMTID 时**不伪造** —— 直连时代实测
 *    伪造 NMTID 打登录直接 -462（见 memory topics/09），宁缺毋滥。
 * 2. 响应不按参考实现「code 非 200 就 reject」，原样交回调用方分层处理；
 *    app 侧的错误翻译比 reject 更细。
 * 3. **weapi 通道内置明文兜底**（app 侧加的，参考实现没有）：weapi 响应连 JSON
 *    都解不出来（通道级拦截，0 字节 / HTML）时，自动换明文通道打同一端点再发
 *    一次 —— 用户实网 weapi 会被整条拦空，没有这层账号区端点（歌单/账号/日推）
 *    全是空。登录类端点的明文兜底不走这条（端点形状不同，在 NeteaseClient 里）。
 */
object Ncm {
    // ------------------------------------------------------------ 域名与 UA（util/config.json + userAgentMap）

    /** 主站域名。登录类端点的明文兜底必须打这里（interface 域名不行，直连时代实测）。 */
    const val DOMAIN = "https://music.163.com"
    internal const val API_DOMAIN = "https://interface.music.163.com"
    private const val EAPI_DOMAIN = "https://interfacepc.music.163.com"

    private const val UA_WEAPI =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0"
    internal const val UA_API =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.1.29.205117"
    private const val UA_EAPI =
        "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)"

    private const val OS_PC = "pc"
    private const val APPVER_PC = "3.1.17.204416"
    private const val OSVER_PC = "Microsoft-Windows-10-Professional-build-19045-64bit"
    private const val CHANNEL = "netease"

    /**
     * 一起听会记住建房时声明的客户端版本，官方 App 拒绝它认为过时的成员。
     * 这些请求对外宣称当前移动端，并且只写在这一次请求上，不进会话 cookie。
     */
    enum class ClientIdentity(val os: String, val appver: String, val versioncode: String) {
        MOBILE("android", "9.5.95", "9005095"),
    }

    /** 一次请求的结果。`json == null` 表示响应不是 JSON（网络失败 / 通道被拦 / 加密未解开）。 */
    class Res(
        val status: Int,
        val json: JSONObject?,
        val setCookie: List<String>,
        /** 原始响应体。诊断「通道被拦成 0 字节 / 回了 HTML」时靠它区分。 */
        val body: String = "",
    ) {
        val code: Int get() = json?.optInt("code", status) ?: status
        val bodyBlank: Boolean get() = body.isBlank()
    }

    // ------------------------------------------------------------ 入口

    /**
     * 发一个请求。[crypto] 取 `"weapi"` / `"eapi"` / `"api"`，空串 = 默认 eapi
     * （对应参考项目 encrypt:true 的默认值）。[uri] 一律用 `/api/...` 形态，
     * 与参考项目模块里传给 request() 的 uri 完全一致。
     *
     * [fakeNmtid] = 无真实 NMTID 时要不要按参考实现伪造一条（非 eapi 通道）。
     * 登录 / 发码必须传 false —— 直连时代实测伪造 NMTID 打登录直接 -462。
     *
     * [domain] 只对明文 api 通道生效（对应参考实现 request.js 的 options.domain）：
     * 登录兜底要用 music.163.com（直连时代实测可用），而不是 interface 域名。
     *
     * [plainFallback] = weapi 通道级拦截兜底的开关：weapi 响应**连 JSON 都解不出来**
     * （0 字节 / HTML，对应 topics/09「body 空白」那种通道级拦截）时，自动换明文
     * 通道打**同一端点**再发一次（域名 music.163.com）。拿到 JSON（哪怕 -462）说明
     * 请求到了 handler，不补发。登录类请求自己管兜底（明文端点的 uri 和参数形状
     * 都不同，见 [NeteaseClient] 的 loginExchange），传 false 关掉这一支。
     */
    fun request(
        http: OkHttpClient,
        session: SessionStore,
        uri: String,
        data: JSONObject,
        crypto: String = "",
        fakeNmtid: Boolean = true,
        domain: String = "",
        plainFallback: Boolean = true,
        identity: ClientIdentity? = null,
    ): Res {
        val mode = if (crypto.isEmpty()) "eapi" else crypto
        val first = send(http, session, mode, uri, data, fakeNmtid, domain, identity)
        if (mode == "weapi" && plainFallback && first.json == null && first.status != 0) {
            return send(http, session, "api", uri, data, fakeNmtid, DOMAIN, identity)
        }
        return first
    }

    /** 单次发送：通道分派、cookie 处理、响应解析都在这里；[request] 负责兜底编排。 */
    private fun send(
        http: OkHttpClient,
        session: SessionStore,
        mode: String,
        uri: String,
        data: JSONObject,
        fakeNmtid: Boolean,
        domain: String,
        identity: ClientIdentity?,
    ): Res {
        // 会话 csrf 落定（没有才生成一次并落盘）—— weapi 载荷和 eapi header 都要用
        val csrf = session.getCsrf()
        val cookie = identityCookie(processCookieObject(session, mode, csrf, fakeNmtid), identity)

        val builder = Request.Builder()
        when (mode) {
            "weapi" -> {
                val payload = data.shallowCopy()
                payload.put("e_r", false)
                payload.put("csrf_token", csrf)
                val enc = Crypto.weapiEncrypt(payload.toString())
                builder.post(
                    FormBody.Builder()
                        .add("params", enc.params)
                        .add("encSecKey", enc.encSecKey)
                        .build(),
                )
                builder.url("$DOMAIN/weapi/${uri.removePrefix("/api/")}")
                builder.header("Referer", "$DOMAIN/")
                builder.header("Origin", DOMAIN)
                builder.header("User-Agent", UA_WEAPI)
                builder.header("Cookie", jarCookie(cookie))
            }
            "eapi" -> {
                val header = eapiHeader(cookie, csrf, includeNmtid = true)
                val payload = data.shallowCopy()
                payload.put("e_r", false)
                payload.put("header", header)
                val params = Crypto.eapiEncrypt(uri, payload.toString())
                builder.post(FormBody.Builder().add("params", params).build())
                builder.url("$EAPI_DOMAIN/eapi/${uri.removePrefix("/api/")}")
                builder.header("User-Agent", UA_EAPI)
                builder.header("Cookie", headerCookie(header))
            }
            else -> {
                val payload = data.shallowCopy()
                payload.put("e_r", false)
                builder.post(formOf(payload))
                builder.url("${if (domain.isNotEmpty()) domain else API_DOMAIN}$uri")
                builder.header("User-Agent", UA_API)
                // 明文 api 通道的 Cookie 同样是压平的 header 对象（参考实现 eapi/api 共用这一支）
                builder.header("Cookie", headerCookie(eapiHeader(cookie, csrf, includeNmtid = false)))
            }
        }

        return try {
            http.newCall(builder.build()).execute().use { res ->
                val setCookie = res.headers("Set-Cookie")
                // 服务端下发的一切（登录态、NMTID）都进会话；eapi 不带 NMTID 时
                // 服务端会补发一条真的，captureSetCookie 顺路收好
                session.captureSetCookie(setCookie)
                val text = res.body?.string().orEmpty()
                Res(status = res.code, json = parseResponse(mode, text), setCookie = setCookie, body = text)
            }
        } catch (_: Exception) {
            Res(status = 0, json = null, setCookie = emptyList(), body = "")
        }
    }

    /**
     * 响应体解析：先按 JSON 读；eapi 通道解析不出时，再试一次
     * **十六进制 AES-ECB 解密** —— 网易的 eapi 在部分端点/环境会把响应体整个
     * 加密成 hex 串（老直连版的 eapiJson 一直兜着这一层，移植时差点弄丢）。
     */
    private fun parseResponse(mode: String, text: String): JSONObject? {
        jsonOrNull(text)?.let { return it }
        if (mode != "eapi") return null
        val trimmed = text.trim()
        if (trimmed.length < 32 || !trimmed.matches(Regex("^[0-9A-Fa-f]+$"))) return null
        return runCatching { jsonOrNull(Crypto.eapiDecrypt(trimmed)) }.getOrNull()
    }

    private fun jsonOrNull(body: String): JSONObject? =
        try {
            JSONObject(body)
        } catch (_: Exception) {
            null
        }

    // ------------------------------------------------------------ cookie 处理（processCookieObject 移植）

    private fun processCookieObject(
        session: SessionStore,
        mode: String,
        csrf: String,
        fakeNmtid: Boolean,
    ): Map<String, String> {
        val out = linkedMapOf<String, String>()
        // 会话 cookie + 设备指纹；os 三元组由这里补齐（deviceIdentity 不含 os 本身）
        for ((k, v) in session.getCookies()) if (v.isNotEmpty()) out[k] = v
        out["os"] = out["os"] ?: OS_PC
        out["appver"] = out["appver"] ?: APPVER_PC
        out["osver"] = out["osver"] ?: OSVER_PC
        out["channel"] = out["channel"] ?: CHANNEL
        out["__remember_me"] = out["__remember_me"] ?: "true"
        out["ntes_kaola_ad"] = out["ntes_kaola_ad"] ?: "1"
        out["__csrf"] = csrf

        // NMTID：真实缓存的优先；没有时——
        // - eapi 请求**不带**，等服务端下发（captureSetCookie 会收）；
        // - 其它通道按参考实现伪造一条格式正确的（'00O' + 19 位 hex），不落盘；
        //   登录相关端点传 fakeNmtid = false 关掉这一支（伪造票据打登录 = -462）。
        if (out["NMTID"].isNullOrEmpty() && mode != "eapi" && fakeNmtid) {
            out["NMTID"] = "00O" + randomHex(19)
        }
        return out
    }

    /**
     * 移动端身份只覆盖这一次请求的 os / appver / versioncode。
     * 会话里继续留着 PC 三元组，避免一起听把日常接口的客户端标识带跑。
     */
    private fun identityCookie(cookie: Map<String, String>, identity: ClientIdentity?): Map<String, String> {
        if (identity == null) return cookie
        return cookie.toMutableMap().apply {
            this["os"] = identity.os
            this["appver"] = identity.appver
            this["versioncode"] = identity.versioncode
        }
    }

    private fun randomHex(length: Int): String {
        val pool = "0123456789abcdef"
        return buildString(length) {
            repeat(length) { append(pool[kotlin.random.Random.nextInt(pool.length)]) }
        }
    }

    /** 网易侧不参与鉴权、但格式要对的会话 id（听歌识曲一类的匿名端点用）。 */
    internal fun newSessionId(): String = randomHex(32)

    // ------------------------------------------------------------ eapi header（request.js eapi/api 分支移植）

    private fun eapiHeader(cookie: Map<String, String>, csrf: String, includeNmtid: Boolean): JSONObject {
        val header = JSONObject()
            .put("osver", cookie["osver"] ?: OSVER_PC)
            .put("deviceId", cookie["deviceId"] ?: "hypochlorite")
            .put("os", cookie["os"] ?: OS_PC)
            .put("appver", cookie["appver"] ?: APPVER_PC)
            .put("versioncode", cookie["versioncode"] ?: "140")
            .put("mobilename", cookie["mobilename"] ?: "")
            .put("buildver", cookie["buildver"] ?: (System.currentTimeMillis() / 1000).toString())
            .put("resolution", cookie["resolution"] ?: "1920x1080")
            .put("__csrf", csrf)
            .put("channel", cookie["channel"] ?: CHANNEL)
            .put(
                "requestId",
                "${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(0, 1000).toString().padStart(4, '0')}",
            )
        cookie["MUSIC_U"]?.let { header.put("MUSIC_U", it) }
        cookie["MUSIC_A"]?.let { header.put("MUSIC_A", it) }
        if (includeNmtid) cookie["NMTID"]?.let { header.put("NMTID", it) }
        return header
    }

    // ------------------------------------------------------------ 序列化（cookieObjToString / createHeaderCookie 移植）

    /** weapi 用的完整 cookie jar：`k=v; k=v`，逐个 encodeURIComponent。 */
    private fun jarCookie(cookie: Map<String, String>): String =
        cookie.entries.joinToString("; ") {
            "${enc(it.key)}=${enc(it.value)}"
        }

    /** eapi / 明文 api 用的压平 header 串：同一套编码。 */
    private fun headerCookie(header: JSONObject): String {
        val parts = mutableListOf<String>()
        val keys = header.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            parts.add("${enc(k)}=${enc(header.get(k).toString())}")
        }
        return parts.joinToString("; ")
    }

    /** org.json 没有 copy()，手写浅拷贝 —— 绝不改调用方传入的 JSONObject。 */
    private fun JSONObject.shallowCopy(): JSONObject {
        val out = JSONObject()
        val it = keys()
        while (it.hasNext()) {
            val k = it.next()
            out.put(k, get(k))
        }
        return out
    }

    private fun formOf(data: JSONObject): FormBody {
        val fb = FormBody.Builder()
        val keys = data.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            fb.add(k, data.get(k).toString())
        }
        return fb.build()
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
