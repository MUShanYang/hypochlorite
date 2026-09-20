package app.hypochlorite.netease

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * 加密与 cookie 工具。
 *
 * 请求现在全部走自建的 api-enhanced 代理（见 [NeteaseClient] 头注释），
 * weapi/eapi 的签名、加密、端点路径都由服务端负责，这里只保留三类东西：
 *
 * 1. **纯加密函数**（weapi / eapi）—— 单元测试还在用，而且留着它们对排查协议
 *    问题有用；只是客户端不再拿它们发请求。
 * 2. **cookie 工具**（拼 / 解 / 合并）—— 会话 cookie 要带给代理，代理再转给网易。
 * 3. **少数仍由 app 自己拼的 URL**：免登录的 outer media 直链（播放兜底）、
 *    扫码登录要渲染进二维码的官方登录页地址、一起听的分享页地址。
 */
object Crypto {
    const val WEAPI_PRESET_KEY = "0CoJUm6Qyw8W8jud"
    const val WEAPI_IV = "0102030405060708"
    const val WEAPI_RSA_MODULUS_HEX =
        "00e0b509f6259dc8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876eaa96aa5d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e70"
    val WEAPI_RSA_EXPONENT: BigInteger = BigInteger.valueOf(0x010001)

    const val EAPI_KEY = "e82ckenh8dichen8"
    const val EAPI_SEP = "-36cd479b6b5-"

    /**
     * PC 客户端的 os / appver / osver 三元组，**必须成套出现**。
     *
     * 取值对齐 api-enhanced 的 `osMap.pc`：
     *     os: 'pc', appver: '3.1.17.204416',
     *     osver: 'Microsoft-Windows-10-Professional-build-19045-64bit'
     * 这三个数作为 cookie 带给代理、由代理转给网易，三个数要一起改，别单独拎一个。
     */
    const val PC_OS = "pc"
    const val PC_APPVER = "3.1.17.204416"
    const val PC_OSVER = "Microsoft-Windows-10-Professional-build-19045-64bit"
    const val PC_CHANNEL = "netease"

    /**
     * PC 端 web 的 UA —— 所有**接口请求**都用它。
     */
    const val PC_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    /**
     * 手机端 UA，**只给内嵌的云盾验证页用**。别拿它去发接口请求，也别拿 PC 的来开验证页。
     *
     * 那个页面打开后第一件事就是判环境（bundle 里）：
     * ```
     * bn() = 在网易云音乐 App 内（UA 里有 NeteaseMusic/x.y.z）
     * _n() = 手机浏览器 && !bn()
     * (bn() || _n()) ? 渲染滑块 : 只渲染一句「请在网易云音乐app内打开本页面」
     * ```
     * 也就是说**只有「PC 浏览器且不在 App 内」会被拒**。
     */
    const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; SM-S9180 Build/TP1A.220624.014) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.5615.136 " +
            "Mobile Safari/537.36"

    /**
     * **验证页专用 UA** —— 手机 UA + 尾巴上的 `NeteaseMusic/9.1.65`。
     *
     * 验证页的渲染判据是 UA 里有没有 `NeteaseMusic/x.y.z`（`isInMusic()`），
     * 跟手不手机无关。所以 [MOBILE_USER_AGENT] 单独用**永远**只会得到那句
     * 「请在 app 内打开本页面」。尾巴上的版本号随便写个真实存在的即可 ——
     * 页面只 `test()` 有没有这个模式，不校验版本，也不校验 `window.MNB` 原生桥。
     */
    const val VERIFY_USER_AGENT = MOBILE_USER_AGENT + " NeteaseMusic/9.1.65"

    /** 房主 / 成员在分享链接里的固定参数位 */
    const val LT_SHARE_BASE = "https://st.music.163.com/listen-together/share/"

    fun ltShareUrl(roomId: String, songId: String? = null, inviterId: String? = null): String {
        val sb = StringBuilder(LT_SHARE_BASE).append("?roomId=").append(roomId)
        if (!songId.isNullOrEmpty()) sb.append("&songId=").append(songId)
        if (!inviterId.isNullOrEmpty()) sb.append("&inviterId=").append(inviterId)
        return sb.toString()
    }

    private const val BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val PRESET_KEY = WEAPI_PRESET_KEY.toByteArray(Charsets.UTF_8)
    private val IV = WEAPI_IV.toByteArray(Charsets.UTF_8)
    private val EAPI_KEY_BUF = EAPI_KEY.toByteArray(Charsets.UTF_8)

    /**
     * 兜底 `__csrf`，**整个进程只生成一次**。
     *
     * 正常调用路径会先用 [SessionStore.getCsrf] 落盘一个固定的，这里只是
     * 没有会话对象时的兜底（比如单元测试），也得是稳定的。
     */
    private val fallbackCsrf: String by lazy {
        java.util.UUID.randomUUID().toString().replace("-", "")
    }

    fun randomSecretKey(length: Int = 16): String {
        val bytes = Random.Default.nextBytes(length)
        return buildString(length) {
            for (b in bytes) append(BASE62[(b.toInt() and 0xff) % 62])
        }
    }

    fun aesEncrypt(text: ByteArray, key: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(IV))
        return Base64.getEncoder().encodeToString(cipher.doFinal(text))
    }

    fun aesEncrypt(text: String, key: ByteArray): String = aesEncrypt(text.toByteArray(Charsets.UTF_8), key)

    fun aesDecrypt(b64: String, key: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(IV))
        val raw = Base64.getDecoder().decode(b64)
        return String(cipher.doFinal(raw), Charsets.UTF_8)
    }

    fun rsaEncrypt(text: String): String {
        val reversed = text.toByteArray(Charsets.UTF_8).reversedArray()
        val m = BigInteger(1, reversed)
        val n = BigInteger(WEAPI_RSA_MODULUS_HEX, 16)
        val c = m.modPow(WEAPI_RSA_EXPONENT, n)
        return c.toString(16).padStart(256, '0')
    }

    fun weapiEncrypt(json: String, secretKey: String = randomSecretKey()): WeapiEnc {
        val params = aesEncrypt(aesEncrypt(json, PRESET_KEY).toByteArray(Charsets.UTF_8), secretKey.toByteArray(Charsets.UTF_8))
        return WeapiEnc(params = params, encSecKey = rsaEncrypt(secretKey), secretKey = secretKey)
    }

    fun weapiDecryptParams(params: String, secretKey: String): String {
        val inner = aesDecrypt(params, secretKey.toByteArray(Charsets.UTF_8))
        return aesDecrypt(inner, PRESET_KEY)
    }

    fun serializeCookies(cookies: Map<String, String>): String =
        cookies.entries
            .filter { it.value.isNotEmpty() }
            .joinToString("; ") { "${it.key}=${it.value}" }

    fun parseCookieString(input: String?): Map<String, String> {
        if (input.isNullOrBlank()) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (part in input.split(";")) {
            val p = part.trim()
            val eq = p.indexOf('=')
            if (eq <= 0) continue
            val name = p.substring(0, eq).trim()
            val value = p.substring(eq + 1).trim()
            if (name.isEmpty()) continue
            if (name.matches(Regex("^(Path|Domain|Expires|Max-Age|HttpOnly|Secure|SameSite)$", RegexOption.IGNORE_CASE))) {
                continue
            }
            out[name] = value
        }
        return out
    }

    fun parseSetCookie(setCookie: List<String>?): Map<String, String> {
        val out = linkedMapOf<String, String>()
        if (setCookie == null) return out
        for (line in setCookie) {
            val first = line.split(";")[0]
            val eq = first.indexOf('=')
            if (eq <= 0) continue
            out[first.substring(0, eq).trim()] = first.substring(eq + 1).trim()
        }
        return out
    }

    /**
     * 拼出网易的默认 cookie。`extra` 后写，所以调用方带进来的键一律覆盖默认值。
     *
     * 传进来的 `extra` 通常来自 [SessionStore.getCookies]，
     * 里面已经含了设备指纹（deviceId / _ntes_nuid / _ntes_nnid / WNMCID）。
     * 代理（api-enhanced 的 request 层）会把它们原样转给网易。
     */
    fun defaultNeteaseCookies(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val out = linkedMapOf(
            "os" to PC_OS,
            "appver" to PC_APPVER,
            "osver" to PC_OSVER,
            "channel" to PC_CHANNEL,
        )
        out.putAll(extra)
        if (!out.containsKey("__csrf") && !out.containsKey("csrf")) {
            out["__csrf"] = fallbackCsrf
        }
        return out
    }

    fun buildNeteaseHeaders(cookies: Map<String, String> = emptyMap(), extra: Map<String, String> = emptyMap()): Map<String, String> {
        val out = linkedMapOf(
            "User-Agent" to PC_USER_AGENT,
            "Referer" to "https://music.163.com/",
            "Origin" to "https://music.163.com",
            "Cookie" to serializeCookies(defaultNeteaseCookies(cookies)),
        )
        out.putAll(extra)
        return out
    }

    fun eapiEncrypt(apiPath: String, json: String): String {
        val digest = md5Hex("nobody${apiPath}use${json}md5forencrypt")
        val data = "${apiPath}${EAPI_SEP}${json}${EAPI_SEP}$digest"
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(EAPI_KEY_BUF, "AES"))
        val enc = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        return enc.toHex().uppercase(Locale.US)
    }

    fun eapiDecrypt(hex: String): String {
        val buf = hex.trim().hexToBytes()
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(EAPI_KEY_BUF, "AES"))
        return String(cipher.doFinal(buf), Charsets.UTF_8)
    }

    fun eapiDecryptParams(hex: String): EapiPlain {
        val plain = eapiDecrypt(hex)
        val first = plain.indexOf(EAPI_SEP)
        val last = plain.lastIndexOf(EAPI_SEP)
        if (first < 0 || last <= first) return EapiPlain(url = "", dataJson = null, raw = plain)
        return EapiPlain(
            url = plain.substring(0, first),
            dataJson = plain.substring(first + EAPI_SEP.length, last),
            digest = plain.substring(last + EAPI_SEP.length),
            raw = plain,
        )
    }

    fun outerMediaUrl(songId: String): String =
        "https://music.163.com/song/media/outer/url?id=${songId.toLongOrNull() ?: 0}.mp3"

    fun loginQrUrl(unikey: String): String = "https://music.163.com/login?codekey=${java.net.URLEncoder.encode(unikey, "UTF-8")}"

    fun md5Hex(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return d.toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        val clean = trim()
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            out[i / 2] = clean.substring(i, i + 2).toInt(16).toByte()
            i += 2
        }
        return out
    }
}

data class WeapiEnc(
    val params: String,
    val encSecKey: String,
    val secretKey: String,
)

data class EapiPlain(
    val url: String,
    val dataJson: String?,
    val digest: String = "",
    val raw: String,
)
