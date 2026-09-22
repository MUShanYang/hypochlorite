package app.hypochlorite.ui

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.hypochlorite.ui.theme.MonetPalette
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * 当前封面铺成全屏虚化底，再用取色 seed 上一层色。
 *
 * 分工：这一层只负责「图 + 取色上色」。可读性遮罩（[MonetPalette.background] 半透明）
 * 由 [monetBackdrop] 画，切歌 reveal 才能继续对遮罩做 crossfade。
 *
 * API 31+ 走 GPU `RenderEffect` 模糊；更低版本把很小的图拉满屏幕，观感接近糊底。
 */
@Composable
fun CoverBlurBackdrop(
    coverUrl: String?,
    palette: MonetPalette,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val url = coverUrl?.takeIf { it.isNotEmpty() }
    val sdk = Build.VERSION.SDK_INT
    val decodePx = if (sdk >= 31) 256 else 48
    val requestPx = if (sdk >= 31) CoverUrls.BACKDROP_PX else 64
    val blurMod = if (sdk >= 31) {
        Modifier.blur(36.dp, BlurredEdgeTreatment.Unbounded)
    } else {
        Modifier
    }
    val luma = coverLumaMatrix(palette.isLight)
    val wash = coverWashColor(palette)

    Crossfade(
        targetState = url,
        animationSpec = tween(480, easing = FastOutSlowInEasing),
        label = "coverBlur",
        modifier = modifier.clipToBounds(),
    ) { current ->
        if (current == null) return@Crossfade
        val request = remember(current, requestPx, decodePx) {
            ImageRequest.Builder(context)
                .data(CoverUrls.sized(current, requestPx))
                .size(decodePx)
                .crossfade(false)
                .build()
        }
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(luma),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.18f
                        scaleY = 1.18f
                    }
                    .then(blurMod),
            )
            if (wash != Color.Transparent) {
                Box(Modifier.fillMaxSize().background(wash))
            }
        }
    }
}

/**
 * 压在虚化封面上的取色遮罩。没有封面时退回不透明底，reveal 的 from/to 都走这里，
 * 透明度才不会在切歌时从 1 跳到 0.6。
 */
fun MonetPalette.coverScrim(coverVisible: Boolean): Color {
    if (!coverVisible) return background
    val alpha = if (isLight) 0.74f else 0.62f
    return background.copy(alpha = alpha)
}

/** 取色上色：seed 的展示色半透明铺上去，封面明暗还在，色相跟过去。 */
private fun coverWashColor(palette: MonetPalette): Color {
    if (palette.seed == null) return Color.Transparent
    val alpha = if (palette.isLight) 0.22f else 0.38f
    return palette.seedColor.copy(alpha = alpha)
}

private fun coverLumaMatrix(isLight: Boolean): ColorMatrix {
    val s = if (isLight) 0.88f else 0.58f
    return ColorMatrix(
        floatArrayOf(
            s, 0f, 0f, 0f, 0f,
            0f, s, 0f, 0f, 0f,
            0f, 0f, s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}
