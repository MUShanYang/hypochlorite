package app.hypochlorite.ui.theme

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.hypochlorite.R

val White = Color(0xFFFFFFFF)
val Black = Color(0xFF000000)
val Ink = Color(0xFFF5F5F5)
val DarkInk = Color(0xFF111111)
val Mute = Color(0xFF888888)
val DarkMute = Color(0xFF737373)
val Warn = Color(0xFFFF1212)
val CoverBg = Color(0xFF111111)
val LightCoverBg = Color(0xFFEBEBEF)
val QrDark = Color(0xFF111111)

/**
 * 压在任意底色上的对比色：亮底黑、暗底白。
 *
 * 阈值取 **0.179**，不是随手写的 0.45 —— 这是「白字对比度 = 黑字对比度」的解：
 * 白字 `1.05/(L+0.05)`、黑字 `(L+0.05)/0.05`，联立得 `L = √0.0525 - 0.05 ≈ 0.179`。
 * 取 0.45 会留下一段死区（L 在 0.18~0.45 之间时白字只有 2~4:1），
 * 中亮度封面的横幅文字会糊成一片。
 *
 * 用于「底色本身是取色结果」的场景（横幅、取色方块描边）——
 * 这些地方不能用 `colors.text`：白天模式的 text 是近黑的墨色，
 * 压在同样深沉的 accent 上会直接看不见。
 */
fun Color.onColor(): Color = if (luminance() > 0.179f) Color.Black else Color.White

data class HypochloriteColors(
    val isLight: Boolean = false,
    val background: Color = Black,
    val text: Color = Ink,
    val muted: Color = Mute,
    val warning: Color = Warn,
    val cover: Color = CoverBg,
    val surface: Color = CoverBg,
    val accent: Color = Warn,
    /** 切歌横幅 / 漫游扫屏底色，取色时跟随封面真实原色 */
    val banner: Color = Color.White,
    /** 压在 banner 上的文字颜色，根据底色明度自适应（亮底黑字，暗底白字）保证高对比度与绝对可读性 */
    val onBanner: Color = banner.onColor(),
    /**
     * 详情页歌名 / 大标题的墨色。白天取色 = accent（取色改暗），其余 = text。
     */
    val titleInk: Color = Ink,
    /**
     * 压在 banner 取色块上的墨色（切歌横幅三处）。
     * 白天取色 = 同色相 tone 14 暗墨；深色取色 = text；纯黑白 = banner 的对比色。
     */
    val bannerInk: Color = Ink,
    /** 压在背景/卡面之上的遮罩底色（浅色模式用白纱、深色模式用黑纱） */
    val scrim: Color = if (isLight) Color.White else Color.Black,
)

/**
 * 用 compositionLocalOf 而不是 staticCompositionLocalOf：这里的值每帧都在动，
 * static 版会把整棵子树全部重组，动态版只重组真正读它的节点。
 */
val LocalHypochloriteColors = compositionLocalOf { HypochloriteColors() }

val HeavyBold = FontWeight(1000)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val HypochloriteFont = FontFamily(
    Font(R.font.google_sans_flex, FontWeight.W100, variationSettings = FontVariation.Settings(FontVariation.weight(100))),
    Font(R.font.google_sans_flex, FontWeight.W200, variationSettings = FontVariation.Settings(FontVariation.weight(200))),
    Font(R.font.google_sans_flex, FontWeight.W300, variationSettings = FontVariation.Settings(FontVariation.weight(300))),
    Font(R.font.google_sans_flex, FontWeight.W400, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.google_sans_flex, FontWeight.W500, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.google_sans_flex, FontWeight.W600, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.google_sans_flex, FontWeight.W700, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.google_sans_flex, FontWeight.W800, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
    Font(R.font.google_sans_flex, FontWeight.W900, variationSettings = FontVariation.Settings(FontVariation.weight(900))),
    Font(R.font.google_sans_flex, HeavyBold, variationSettings = FontVariation.Settings(FontVariation.weight(1000))),
)

val BodyStyle = TextStyle(
    fontFamily = HypochloriteFont,
    fontWeight = FontWeight.Normal,
    fontSize = 16.sp,
    lineHeight = 20.sp,
    color = Color.Unspecified,
)

/** 警告语义固定为红，不跟随封面取色 */
private fun MonetPalette.toHypochloriteColors(): HypochloriteColors = HypochloriteColors(
    isLight = isLight,
    background = background,
    text = text,
    muted = muted,
    warning = Warn,
    cover = surface,
    surface = surface,
    accent = accent,
    banner = banner,
    onBanner = banner.onColor(),
    titleInk = titleInk,
    bannerInk = bannerInk,
)

/**
 * 主题下发。
 *
 * **颜色一律直给目标值，不做交叉淡入淡出** —— 这是一条踩过坑才定下来的红线。
 *
 * 曾经给 background / text / muted / surface 挂了 `animateColorAsState`（300ms + 120ms 延迟），
 * 想做出「先铺底、后变字」的错开感。但真正的背景是 `MonetBackground` 用目标色直接画的，
 * 两者根本不同步：深色 → 白天模式时背景已经变白，文字还在 120ms 的延迟里保持白色，
 * 屏幕上是「白字压白底」，字要等 400ms 才重新出现。
 *
 * 就算把两边都同步成动画也不行：深色 ↔ 白色的交叉淡化在中点必然撞成同一个灰，
 * 对比度 1:1。所以正确的分工是 —— **颜色瞬切，平滑过渡交给 reveal 的 crossfade**
 * （`RevealEase` 是重度前置的，42ms 就走完 43% 的进度，不可读窗口只有一两帧）。
 */
@Composable
fun HypochloriteTheme(
    palette: MonetPalette = MonetPalette.Mono,
    content: @Composable () -> Unit,
) {
    val colors = palette.toHypochloriteColors()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.isAppearanceLightStatusBars = colors.isLight
                insetsController.isAppearanceLightNavigationBars = colors.isLight
            }
        }
    }

    CompositionLocalProvider(LocalHypochloriteColors provides colors) {
        content()
    }
}
