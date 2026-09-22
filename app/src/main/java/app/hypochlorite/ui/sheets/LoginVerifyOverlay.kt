package app.hypochlorite.ui.sheets

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.hypochlorite.HypochloriteViewModel
import app.hypochlorite.netease.Crypto
import app.hypochlorite.ui.Hairline
import app.hypochlorite.ui.HoverBold
import app.hypochlorite.ui.MonoText
import app.hypochlorite.ui.theme.LocalHypochloriteColors

/**
 * 云盾人机验证页，内嵌 WebView。
 *
 * 两个关键点，少一个这页就白开了：
 * 1. **先把 app 会话的 cookie 种进去** —— 否则验证的是一个陌生会话，过完也对不上这次登录；
 * 2. **验证完把 cookie 收回来** —— 云盾的验证结果就是 cookie，待在 WebView 的 jar 里
 *    等于没验证。所以「已完成验证」要把 jar 里的东西原样交回 ViewModel。
 */
@Composable
internal fun LoginVerifyOverlay(url: String, vm: HypochloriteViewModel) {
    val colors = LocalHypochloriteColors.current
    BackHandler { vm.closeLoginVerify() }
    val seedCookies = remember(url) { vm.verifySeedCookies() }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MonoText("安全验证", modifier = Modifier.weight(1f))
                HoverBold("关闭", onClick = { vm.closeLoginVerify() }, color = colors.muted, padV = 8)
            }
            Hairline()
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // **必须带 `NeteaseMusic/x.y.z`**。这个页面挂载前只判一件事：
                        // `bn()`（UA 匹配 /NeteaseMusic\/\d+.\d+.\d+/i），为假就直接
                        // 渲染「请在网易云音乐app内打开本页面」+ 转圈，滑块一次都不出现。
                        // 手机 UA 单独用是不够的 —— 详见 Crypto.VERIFY_USER_AGENT。
                        settings.userAgentString = Crypto.VERIFY_USER_AGENT
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        seedCookies.forEach { (k, v) ->
                            // 两个域都要种：页面自己挂在 st.music.163.com 上，
                            // 但它发给后端的 getConfig / check 要带去 music.163.com 的会话
                            CookieManager.getInstance().setCookie(VERIFY_COOKIE_URL, "$k=$v")
                            CookieManager.getInstance().setCookie(VERIFY_HOST_URL, "$k=$v")
                        }
                        CookieManager.getInstance().flush()
                        webViewClient = object : WebViewClient() {
                            // 只让它留在网页里。这个页面自带一整套「唤起网易云 App」逻辑：
                            // Android 上直接 `location.href = "intent://…#Intent;scheme=orpheus;
                            // package=com.netease.cloudmusic;end"`，兜底还跳 `orpheus://openurl?url=…`、
                            // `neplay://`。WebView 里没有能接这些 scheme 的东西，整页立刻变成
                            // ERR_UNKNOWN_URL_SCHEME —— 验证页当场作废。返回 true = 我们吃掉了这一跳。
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean = !isVerifyPageKept(request.url)
                        }
                        loadUrl(url)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                onRelease = { it.destroy() },
            )
            Hairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HoverBold(
                    "已完成验证",
                    onClick = { vm.completeLoginVerify(harvestVerifyCookies()) },
                    on = true,
                    padV = 9,
                )
                MonoText(
                    "验证通过后点这里，会自动回到登录。",
                    muted = true,
                    size = 12,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 这次导航要不要真的加载。
 *
 * 放行 http/https，但**顺手挡掉两种「把用户带走」的跳转**：
 *
 * 1. **非 http(s) 的 scheme** —— `orpheus://` / `neplay://` / `intent://` / `itms-appss://`。
 *    WebView 没有能处理它们的 Activity，会整页变成 `ERR_UNKNOWN_URL_SCHEME`；
 * 2. **唤起 App 失败后的兜底跳转** —— 网易的唤醒逻辑等 3 秒没唤起成功，就把页面跳到
 *    `music.163.com/m/download` 或 App Store，验证页会被顶掉。
 *
 * 拦掉这些是安全的：验证本身走的是页面内的 XHR + cookie，不依赖任何跳转。
 */
private fun isVerifyPageKept(url: Uri): Boolean {
    val scheme = url.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return false
    val host = url.host?.lowercase().orEmpty()
    if (host.endsWith("itunes.apple.com") || host.endsWith("apps.apple.com")) return false
    return !url.path.orEmpty().contains("/m/download")
}

/** 把 WebView 的 cookie jar 原样读出来交回会话。 */
private fun harvestVerifyCookies(): String {
    val cm = CookieManager.getInstance()
    cm.flush()
    return listOf(VERIFY_COOKIE_URL, VERIFY_HOST_URL)
        .mapNotNull { cm.getCookie(it) }
        .joinToString("; ")
}

private const val VERIFY_COOKIE_URL = "https://music.163.com"
private const val VERIFY_HOST_URL = "https://st.music.163.com"

/** 登录提示里包含这些词就按错误色显示。 */
