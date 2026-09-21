package app.hypochlorite.ui.theme

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 封面驱动的「莫奈取色」（零依赖实现）。
 *
 * 与系统壁纸取色同构：封面 → 主导色 seed → 一套 tonal palette → 深色背景 token。
 * 关键取舍：**只借封面的色相与彩度，不借明度**。明度全部由 tone 决定（tone 即 L*，0 = 黑、100 = 白），
 * 所以纯黑极简的基调不会被彩色封面冲垮 —— 背景只是「带一层极轻色相的黑」。
 *
 * 与系统版的差别：系统 13 档 tone 是离散资源，这里直接按任意 tone 现算；
 * 量化用「色相分桶加权」代替 QuantizerCelebi + Score，观感接近，稳健性略低。
 */
data class MonetPalette(
    /** 封面 seed；null 表示未取色（纯黑白原生主题） */
    val seed: Int? = null,
    val isLight: Boolean = false,
    val background: Color,
    val surface: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    /**
     * 扩散动画的前沿亮色。
     */
    val bloom: Color,
    /**
     * 切歌横幅 / 漫游扫屏的底色。取色时使用封面真实提取的主题色。
     */
    val banner: Color = if (seed == null) (if (isLight) Color(0xFF27272A) else Color(0xFFD4D4D8)) else Color(seed),
    /**
     * 封面真实提取的主题原色（经饱和与明度自适应校准），用于封面画框与动力学动效。
     */
    val seedColor: Color = if (seed == null) (if (isLight) Color(0xFF27272A) else Color(0xFFD4D4D8)) else Color(seed),
    /**
     * 详情页歌名 / 大标题的墨色。取色 + 白天模式 = accent（取色改暗，压纸白底最差 4.7:1）；
     * 深色模式与纯黑白主题 = text（保持原状）。
     */
    val titleInk: Color = text,
    /**
     * 压在 banner 取色块上的文字墨色。banner 自身明度被 clamp 在 L* 40~60（白天），
     * 字必须比底更暗才压得住：白天取色 = 同色相 tone 14 的暗墨（蓝封面 → #002C6A 级别的深藏色，
     * 最差 2.17:1，与纯黑墨的 2.52:1 同档但带色相）；深色取色 = text；纯黑白主题 = banner 的对比色
     * （MonoLight 横幅是深灰块 #27272A，用 text 反而读不出 —— 这里顺带修正）。
     */
    val bannerInk: Color = text,
) {
    companion object {
    /** 原生纯深色 */
    val MonoDark = MonetPalette(
        seed = null,
        isLight = false,
        background = Black,
        surface = CoverBg,
        text = Ink,
        muted = Mute,
        accent = Warn,
        bloom = Black,
        banner = Color(0xFFD4D4D8),
        seedColor = Color(0xFFD4D4D8),
        titleInk = Ink,
        // 纯黑白主题的横幅是亮灰块，压对比色（黑字）
        bannerInk = Color.Black,
    )

    /** 原生纯白天模式 */
    val MonoLight = MonetPalette(
        seed = null,
        isLight = true,
        background = Color(0xFFFFFFFF),
        surface = Color(0xFFF2F2F5),
        text = Color(0xFF111111),
        muted = Color(0xFF737373),
        accent = Warn,
        bloom = Color(0xFFFFFFFF),
        banner = Color(0xFF27272A),
        seedColor = Color(0xFF27272A),
        titleInk = Color(0xFF111111),
        // 纯黑白主题的横幅是深灰块，压对比色（白字）—— 用 text 反而读不出
        bannerInk = Color.White,
    )

        /** 原生纯黑白，也是取色失败时的回落值（选用有质感的银灰而不是过曝纯白） */
        val Mono = MonoDark
    }
}

object Monet {

    /**
     * 背景明度。Material 3 的 dark surface 是 tone 6，但那个在纯黑基底上几乎看不出颜色，
     * 取 12（M3 dark 的 surfaceContainer）—— 仍是深色，却一眼能看出封面带来的色相。
     */
    private const val TONE_BACKGROUND = 12.0
    private const val TONE_SURFACE = 19.0
    private const val TONE_TEXT = 96.0
    private const val TONE_MUTED = 62.0
    private const val TONE_ACCENT = 74.0

    /** 扩散前沿亮边的明度：比背景亮一档才看得见，又不至于闪 */
    private const val TONE_BLOOM = 52.0

    /** 白天模式明度色阶（高对比度 + 柔和纸张质感，绝不泛白刺眼） */
    private const val TONE_BACKGROUND_LIGHT = 96.8
    private const val TONE_SURFACE_LIGHT = 91.5
    private const val TONE_TEXT_LIGHT = 12.0
    private const val TONE_MUTED_LIGHT = 42.0
    private const val TONE_ACCENT_LIGHT = 42.0
    private const val TONE_BLOOM_LIGHT = 88.0

    /**
     * 白天模式横幅墨的明度：必须压得住 L* 40~60 的取色横幅底，
     * 又要比纯黑多一点色相（打表见 verify_tinted_ink.py：tone 14 最差 2.17:1，
     * 蓝封面出 #002C6A 级深藏色 —— 「取色改暗」而不是黑）。
     */
    private const val TONE_BANNER_INK_LIGHT = 14.0

    /** 彩度上限：背景几乎不带色，强调色才允许饱和 */
    private const val CHROMA_NEUTRAL = 13.0
    private const val CHROMA_VARIANT = 20.0
    private const val CHROMA_ACCENT = 56.0

    /** seed 彩度低于此值视为灰阶封面，不硬塞色相 */
    private const val CHROMA_FLOOR = 8.0

    private const val HUE_BUCKETS = 12

    // ---------------------------------------------------------------- 取色

    /**
     * 从封面里提取最具人眼主观代表性的主导色。
     *
     * 针对「取色过白、未正确取色」的根治方案：
     * 1. 双通道分流机制（Chromatic First）：
     *    若画面存在彩度 Chroma >= 14.0 的像素，无条件在彩色像素中遴选；
     * 2. 强力过滤大面积浅白背景：
     *    L* > 74.0 的极浅、米白、反光高亮区域被施加指数级明度惩罚（低于 0.05），
     *    防止 70% 面积的白色墙面/画布压制画面中心只有 5% 的真实彩色主体；
     * 3. 彩度指数增益（Chroma^1.35）：
     *    对红、蓝、黄、绿等高饱和度主体给予强力支持，人眼第一感认知的封面色彩绝大多数是高饱和特征色；
     * 4. 纯黑白封面优雅中灰保底：
     *    仅当整张图片全无彩色时，回落到 40 <= L* <= 65 的质感中性灰，绝不出苍白刺眼的高亮白色。
     */
    fun extractSeed(source: Bitmap): Int? = runCatching {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        // 15-bit RGB 空间量化 (32 * 32 * 32 = 32768 bins)
        val hist = IntArray(32768)
        val sumR = LongArray(32768)
        val sumG = LongArray(32768)
        val sumB = LongArray(32768)

        var validCount = 0
        for (pixel in pixels) {
            if (((pixel ushr 24) and 0xFF) < 180) continue // 忽略透明像素
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            val bin = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            hist[bin]++
            sumR[bin] += r.toLong()
            sumG[bin] += g.toLong()
            sumB[bin] += b.toLong()
            validCount++
        }

        if (validCount == 0) return@runCatching null

        var bestChromaticColor = -1
        var bestChromaticScore = -1.0

        var bestFallbackColor = -1
        var maxFallbackCount = 0

        for (bin in 0 until 32768) {
            val count = hist[bin]
            if (count < 3) continue // 过滤孤立噪点

            val r = (sumR[bin] / count).toInt().coerceIn(0, 255)
            val g = (sumG[bin] / count).toInt().coerceIn(0, 255)
            val b = (sumB[bin] / count).toInt().coerceIn(0, 255)

            val lab = rgbToLab(r, g, b)
            val chroma = hypot(lab.a, lab.b)
            val l = lab.l
            val argb = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

            // 1. 黑白灰/中灰备选（仅在无彩色时使用，锁定在质感中灰区间 35..68，避开死白死黑）
            if (l in 35.0..68.0 && count > maxFallbackCount) {
                maxFallbackCount = count
                bestFallbackColor = argb
            }

            // 2. 彩色候选判定：要求具备基本的彩色辨识度，且明度不能过白或死黑
            if (chroma < 14.0 || l < 16.0 || l > 78.0) {
                continue
            }

            // 面积占比得分（采用 0.35 次方弱化大面积背景垄断，保护 3%~10% 的高饱和视觉焦点）
            val popFraction = count.toDouble() / validCount
            val popScore = popFraction.pow(0.35)

            // 彩度得分：高彩度获得高额权重（(chroma / 25)^1.35）
            val chromaScore = (chroma / 25.0).pow(1.35).coerceIn(0.4, 4.0)

            // 明度舒适区：黄金区间 35..65，越靠近 78 越迅速惩罚（防止米白偏色）
            val lumScore = when {
                l in 35.0..65.0 -> 1.0
                l > 65.0 -> ((78.0 - l) / 13.0).pow(1.5).coerceIn(0.05, 1.0)
                else -> ((l - 16.0) / 19.0).coerceIn(0.2, 1.0)
            }

            val score = popScore * chromaScore * lumScore
            if (score > bestChromaticScore) {
                bestChromaticScore = score
                bestChromaticColor = argb
            }
        }

        if (bestChromaticColor != -1) {
            return@runCatching bestChromaticColor
        }

        bestFallbackColor.takeIf { it != -1 }
    }.getOrNull()

    // ------------------------------------------------------------ 配色推导

    fun derive(seedArgb: Int, isLight: Boolean = false): MonetPalette {
        val lab = rgbToLab(
            (seedArgb shr 16) and 0xFF,
            (seedArgb shr 8) and 0xFF,
            seedArgb and 0xFF,
        )
        val rawChroma = hypot(lab.a, lab.b)
        val hue = (Math.toDegrees(atan2(lab.b, lab.a)) + 360.0) % 360.0
        val chroma = if (rawChroma < CHROMA_FLOOR) 0.0 else rawChroma

        val neutral = chroma.coerceAtMost(CHROMA_NEUTRAL)
        val variant = chroma.coerceAtMost(CHROMA_VARIANT)
        // 灰阶封面（chroma 被 [CHROMA_FLOOR] 归零）必须整条链都掉回中性色。
        // 这里曾经写成 `coerceAtLeast(CHROMA_VARIANT)`，于是 chroma=0 的封面会被顶到 20，
        // 而中性色的色相是纯噪声 → 黑白封面配一个随机色相的 accent，白天模式下尤其难看得明显。
        val accentChroma = if (chroma <= 0.0) 0.0 else chroma.coerceIn(CHROMA_VARIANT, CHROMA_ACCENT)

        if (isLight) {
            val displayTone = if (chroma >= CHROMA_FLOOR) lab.l.coerceIn(40.0, 60.0) else lab.l.coerceIn(38.0, 58.0)
            val displayColor = Color(labToArgb(displayTone, lab.a, lab.b))
            val accentColor = tone(hue, accentChroma, TONE_ACCENT_LIGHT)

            return MonetPalette(
                seed = seedArgb,
                isLight = true,
                background = tone(hue, neutral, TONE_BACKGROUND_LIGHT),
                surface = tone(hue, neutral, TONE_SURFACE_LIGHT),
                text = tone(hue, neutral, TONE_TEXT_LIGHT),
                muted = tone(hue, variant, TONE_MUTED_LIGHT),
                accent = accentColor,
                bloom = tone(hue, accentChroma, TONE_BLOOM_LIGHT),
                banner = displayColor,
                seedColor = displayColor,
                // 歌名 = 取色改暗（accent 本身就是 tone 42 的取色色）
                titleInk = accentColor,
                // 横幅字 = 同色相再暗两档，压得住取色横幅底
                bannerInk = tone(hue, accentChroma, TONE_BANNER_INK_LIGHT),
            )
        } else {
            // 适度保留原图的明度特性
            val naturalTone = lab.l.coerceIn(45.0, 76.0)

            // 对横幅与动力学封面方块展示用的主题色进行明度校正（clamp 至 42..65），
            // 彻底杜绝由于原色过亮导致的「苍白无色」视觉体验，确保色彩浓郁鲜明
            val displayTone = if (chroma >= CHROMA_FLOOR) lab.l.coerceIn(42.0, 65.0) else lab.l.coerceIn(40.0, 60.0)
            val displayColor = Color(labToArgb(displayTone, lab.a, lab.b))

            return MonetPalette(
                seed = seedArgb,
                isLight = false,
                background = tone(hue, neutral, TONE_BACKGROUND),
                surface = tone(hue, neutral, TONE_SURFACE),
                text = tone(hue, neutral, TONE_TEXT),
                muted = tone(hue, variant, TONE_MUTED),
                accent = tone(hue, accentChroma, naturalTone),
                bloom = tone(hue, accentChroma, TONE_BLOOM),
                banner = displayColor,
                seedColor = displayColor,
                // 深色模式维持原状：标题与横幅字都跟正文（近白）
                titleInk = tone(hue, neutral, TONE_TEXT),
                bannerInk = tone(hue, neutral, TONE_TEXT),
            )
        }
    }

    /** 两个 seed 的色调是否接近到「不值得播一次扩散动画」 */
    fun looksSame(a: Int, b: Int): Boolean {
        val la = rgbToLab((a shr 16) and 0xFF, (a shr 8) and 0xFF, a and 0xFF)
        val lb = rgbToLab((b shr 16) and 0xFF, (b shr 8) and 0xFF, b and 0xFF)
        val ca = hypot(la.a, la.b)
        val cb = hypot(lb.a, lb.b)
        if (ca < CHROMA_FLOOR && cb < CHROMA_FLOOR) return true
        val ha = (Math.toDegrees(atan2(la.b, la.a)) + 360.0) % 360.0
        val hb = (Math.toDegrees(atan2(lb.b, lb.a)) + 360.0) % 360.0
        val dh = abs(ha - hb).let { if (it > 180.0) 360.0 - it else it }
        val dl = abs(la.l - lb.l)
        return dh < 16.0 && abs(ca - cb) < 12.0 && dl < 16.0
    }

    /** tonal palette 上取一个 tone：tone 就是 L*，色相彩度固定，超色域的通道会被 clamp 成低彩度 */
    private fun tone(hue: Double, chroma: Double, tone: Double): Color {
        val rad = Math.toRadians(hue)
        return Color(labToArgb(tone, chroma * cos(rad), chroma * sin(rad)))
    }

    // -------------------------------------------------------- 色彩空间换算

    private class Lab(val l: Double, val a: Double, val b: Double)

    private fun rgbToLab(r: Int, g: Int, b: Int): Lab {
        val rl = srgbToLinear(r / 255.0)
        val gl = srgbToLinear(g / 255.0)
        val bl = srgbToLinear(b / 255.0)
        val x = (0.4124 * rl + 0.3576 * gl + 0.1805 * bl) / 0.95047
        val y = 0.2126 * rl + 0.7152 * gl + 0.0722 * bl
        val z = (0.0193 * rl + 0.1192 * gl + 0.9505 * bl) / 1.08883
        val fx = pivotRgbToXyz(x)
        val fy = pivotRgbToXyz(y)
        val fz = pivotRgbToXyz(z)
        return Lab(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    private fun labToArgb(l: Double, a: Double, b: Double): Int {
        val fy = (l + 16.0) / 116.0
        val fx = fy + a / 500.0
        val fz = fy - b / 200.0
        val x = 0.95047 * pivotXyzToRgb(fx)
        val y = pivotXyzToRgb(fy)
        val z = 1.08883 * pivotXyzToRgb(fz)
        val r = 3.2406 * x - 1.5372 * y - 0.4986 * z
        val g = -0.9689 * x + 1.8758 * y + 0.0415 * z
        val bb = 0.0557 * x - 0.2040 * y + 1.0570 * z
        return (0xFF shl 24) or
            (channel(linearToSrgb(r)) shl 16) or
            (channel(linearToSrgb(g)) shl 8) or
            channel(linearToSrgb(bb))
    }

    private fun pivotRgbToXyz(t: Double): Double =
        if (t > 0.008856) t.pow(1.0 / 3.0) else 7.787 * t + 16.0 / 116.0

    private fun pivotXyzToRgb(t: Double): Double {
        val cubed = t * t * t
        return if (cubed > 0.008856) cubed else (t - 16.0 / 116.0) / 7.787
    }

    private fun srgbToLinear(c: Double): Double =
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun linearToSrgb(c: Double): Double {
        val v = c.coerceIn(0.0, 1.0)
        return if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1.0 / 2.4) - 0.055
    }

    private fun channel(v: Double): Int = (v * 255.0).roundToInt().coerceIn(0, 255)
}

/** #RRGGBB，设置页里用来显示当前 seed / 背景色 */
fun argbHex(argb: Int): String =
    "#%02X%02X%02X".format((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

/** Compose Color → #RRGGBB */
fun Color.hex(): String =
    "#%02X%02X%02X".format(
        (red * 255f).roundToInt().coerceIn(0, 255),
        (green * 255f).roundToInt().coerceIn(0, 255),
        (blue * 255f).roundToInt().coerceIn(0, 255),
    )
