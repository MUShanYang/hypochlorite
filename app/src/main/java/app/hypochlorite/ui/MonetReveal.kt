package app.hypochlorite.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.hypochlorite.ThemeReveal
import app.hypochlorite.revealRadiusPx
import app.hypochlorite.ui.theme.MonetPalette
import kotlin.math.max
import kotlin.math.pow
import kotlinx.coroutines.withTimeoutOrNull

/** 主体时长；起手极快、尾巴拉长，像墨滴砸进水里 */
private const val REVEAL_MS = 700

/** 系统关闭动画（开发者选项 / 无障碍）时的退化时长 */
private const val REVEAL_MS_REDUCED = 260

/**
 * 半径终点的外扩系数 —— 波纹要「明显冲出屏幕外」。
 *
 * 用户的原话是「没有扩散出去」：屏幕内**还能看见波纹的圆形边界**。要让边界消失，
 * 前沿必须早于动画结束就跑到屏幕之外。
 *
 * 但出屏不能太早 —— 那样屏幕内根本没有扩散过程，只是瞬间变色。所以外扩系数定**终点**，
 * 幂次 [REVEAL_RADIUS_POW] 定**节奏**：2.2 倍意味着基准外还有 55% 的行程在屏幕外跑完，
 * 边界在 127ms 出屏、670ms 于屏外定格，屏幕内不会留下任何没扫到的角落，
 * 收尾时也绝不会有一圈圆边停在那里。
 */
private const val REVEAL_OVERSCAN = 2.2f

/**
 * 半径推进的幂次。
 *
 * 这是控制「边界什么时候跑出屏幕」的**唯一旋钮**，因为 [REVEAL_OVERSCAN] 只定终点，
 * 终点前的全部时间都花在屏幕内。`pow` 越小，起手越陡、边界出屏越早。
 *
 * 但有下限：[RevealEase] 已经是重度前置的缓动（50ms 就走完 48% 的进度），
 * 幂函数再陡一点，两者叠乘会让起手直接爆炸 —— 试过 0.85 时边界 **61ms 就出屏**，
 * 屏幕上只看到三帧半的圆，等于瞬间变色。
 *
 * 1.15 是实测的平衡点：边界在 **127ms（约 7.6 帧）** 才越过屏幕对角线。
 * 配上封面中心那团起手亮核和 70ms 的封面脉动，眼睛足够看清「颜色从封面荡开」，
 * 之后边界跑出屏幕，屏幕内只剩一整块扫过的新底色。
 */
private const val REVEAL_RADIUS_POW = 1.15f

/**
 * 羽化带的相对宽度。**这是前沿唯一的形状** —— 之前前沿有一道亮环、后面还跟着两道
 * 拖尾细环，屏幕上能看见一圈明显的圆边在推进，已经全部去掉。
 *
 * 现在径向渐变只有两个节点：到 `1 - [REVEAL_FEATHER]` 为止是已经沉定的新底色，
 * 之后平滑淡出到透明。前沿就是一团没有轮廓的柔光，靠亮度差被眼睛感知，
 * 而不是靠一条线。
 */
private const val REVEAL_FEATHER = 0.22f

/** 起手色核：占动画前多少比例，以及最大半径占比 —— 颜色「从封面里涌出来」的那一下 */
private const val REVEAL_CORE_SPAN = 0.32f
private const val REVEAL_CORE_RADIUS = 0.26f

/** 刚铺开的区域留一点「余温」，随进度冷却回背景色 */
private const val REVEAL_WARMTH = 0.14f

/** 兜底：动画若因异常没走到终点，超过这个时间强制收尾，避免半途的旧底色永远挂在屏幕上 */
private const val REVEAL_MAX_MS = 2000L

/** 封面脉动：峰值放大比例（设为 1.0f 去除封面弹跳震颤） */
private const val COVER_PULSE_SCALE = 1.0f
private const val COVER_PULSE_ATTACK_MS = 70
private const val COVER_PULSE_MS = 320

private val RevealEase = FastOutSlowInEasing

/**
 * 全屏纯净色彩平滑渐变过渡：
 * 不再使用激光扫描线、箭头指示标或高能粒子，纯粹由旧背景色平滑淡入淡出（Crossfade）至新背景色。
 */
private fun DrawScope.drawMonetReveal(
    overlay: Color,
    from: Color,
    p: Float,
) {
    val t = p.coerceIn(0f, 1f)
    drawRect(lerp(from, overlay, t))
}

/**
 * 封面脉动。
 *
 * 刻意**不用扩散进度来驱动**：扩散的缓动是重度前置的（前 6% 的时间就走完 43% 的进度），
 * 拿进度取比例做曲线，脉冲会在 10ms 内完成 —— 那不是脉动，是一帧的闪。
 * 所以给它一条独立的实时时钟：70ms 冲上峰值，再 250ms 收回。
 *
 * 没有这一下，动画会显得无源之水 —— 颜色凭空从某个点冒出来，而那个点上什么都没发生。
 */
@Composable
fun rememberCoverPulse(reveal: ThemeReveal?): Animatable<Float, AnimationVector1D> {
    val scale = remember { Animatable(1f) }
    val id = reveal?.id
    LaunchedEffect(id) {
        if (id == null) return@LaunchedEffect
        scale.animateTo(
            targetValue = 1f,
            animationSpec = keyframes {
                durationMillis = COVER_PULSE_MS
                1f at 0
                COVER_PULSE_SCALE at COVER_PULSE_ATTACK_MS using FastOutSlowInEasing
                1f at COVER_PULSE_MS using CubicBezierEasing(0.25f, 0f, 0.2f, 1f)
            },
        )
    }
    return scale
}

/** 把 [scale] 作用到封面尺寸上。值在图层阶段读，不触发重组。 */
fun Modifier.coverPulse(scale: Animatable<Float, AnimationVector1D>): Modifier = graphicsLayer {
    scaleX = scale.value
    scaleY = scale.value
}

/**
 * 封面在根坐标系里的中心点，作为扩散起点 —— 波纹从封面所在的位置冒出来。
 *
 * 两个槽位而不是一个：详情页的大封面优先级高于底栏缩略图。
 * 不能靠「后布局的覆盖前面的」，Box 里子节点的布局顺序一变，起点就会跑到别的地方。
 * 详情页关闭时会清掉大封面，自动回落到缩略图的位置。
 */
object CoverAnchor {
    private var primary: Offset? = null
    private var mini: Offset? = null

    val center: Offset? get() = primary ?: mini

    fun setPrimary(value: Offset) {
        primary = value
    }

    fun clearPrimary() {
        primary = null
    }

    fun setMini(value: Offset) {
        mini = value
    }
}

/** 详情页大封面用 */
fun Modifier.reportPrimaryCoverAnchor(): Modifier = onGloballyPositioned { coords ->
    CoverAnchor.setPrimary(coords.centerInRoot())
}

/** 底栏缩略图用 */
fun Modifier.reportMiniCoverAnchor(): Modifier = onGloballyPositioned { coords ->
    CoverAnchor.setMini(coords.centerInRoot())
}

private fun LayoutCoordinates.centerInRoot(): Offset {
    val position = positionInRoot()
    return Offset(
        position.x + size.width / 2f,
        position.y + size.height / 2f,
    )
}

/**
 * 这个背景节点自己在根坐标系里的几何（尺寸 + 左上角）。
 *
 * 不能只用 DrawScope 的 `size` / 原点：调用方可能是根层，也可能是压在上面的详情页卡面 ——
 * 后者有 padding 和拖拽位移，用本地坐标算出来的圆心会偏，半径边界也会落在屏幕里侧，
 * 留下一圈永不扩散的旧底色。
 *
 * 用 [onGloballyPositioned] 拿：它是布局阶段回调，给的坐标已经是根坐标系下的值，
 * 而且节点位置变化（拖拽、padding 变化）会重新回调。draw 阶段只读缓存，不做计算。
 */
private class RevealGeometry {
    var size: Size = Size.Zero
    var topLeft: Offset = Offset.Zero

    val valid: Boolean get() = size.width > 0f && size.height > 0f

    fun update(coordinates: LayoutCoordinates) {
        val s = coordinates.size
        size = Size(s.width.toFloat(), s.height.toFloat())
        topLeft = coordinates.positionInRoot()
    }
}

/**
 * 拿宿主的 Activity。取不到就退回按节点尺寸估算，不影响波纹正确性，只是外扩量少一点。
 *
 * `internal` 而非 `private`：`NowPlayingScreen.kt` 的详情页卡面也要读它，
 * 在 @Composable 层取好再传给 [monetBackdrop]（draw 阶段取不到 Context）。
 */
internal fun Context.activityOrNull(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * 一次扩散动画的进度。每一层背景各自持有一份，但同一个 reveal 同帧起跑，画面完全一致。
 * 返回的是 Animatable 而不是当前值，交给调用方在 draw 阶段读 .value —— 只失效绘制、不触发重组。
 *
 * [onFinished] 带回本次 reveal 的 id：只有它仍然是「当前那一次」时才会被真正收尾，
 * 否则切歌时被取消的旧动画会把刚开始的新动画一并掐掉。
 */
@Composable
fun rememberRevealProgress(
    reveal: ThemeReveal?,
    onFinished: ((Long) -> Unit)? = null,
): Animatable<Float, AnimationVector1D> {
    // 用 ValueAnimator.areAnimatorsEnabled() 而不是 Settings.Global.getFloat：
    // 后者走 ContentResolver 是与系统设置进程的 IPC，在组合阶段（主线程）调用有卡顿风险。
    val animatorsEnabled = remember {
        runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)
    }

    val id = reveal?.id
    val progress = remember(id) { Animatable(0f) }

    LaunchedEffect(id) {
        if (id == null) return@LaunchedEffect
        // 先等一帧：切歌常伴随页面进出，封面可能还没完成布局，太早读会拿到旧位置
        withFrameNanos { }
        try {
            // 加超时兜底：万一帧时钟卡住（后台/被系统压制），也保证 reveal 一定会被清掉
            withTimeoutOrNull(REVEAL_MAX_MS) {
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = if (animatorsEnabled) REVEAL_MS else REVEAL_MS_REDUCED,
                        easing = RevealEase,
                    ),
                )
            }
        } finally {
            // 无论是正常播完还是被取消（切歌 / 离开界面），都必须收尾：
            // 否则 reveal 永远挂着非 null，旧底色会有一块永远停在中途不散。
            onFinished?.invoke(id)
        }
    }

    return progress
}

/**
 * 把「旧底色 + 从封面位置扩散开的新底色」画成这个节点的背景。
 * 起点的位置每帧现读 [CoverAnchor]，所以它永远等于封面当前所在的位置。
 *
 * [activity] 只用于兜底估算波纹半径（分屏 / 窗口大于屏幕时窗口尺寸不够）。
 * 它必须由**调用方的 @Composable 层**读出来传进来 —— 这里跑在 draw 阶段，
 * 不能直接调 `LocalContext.current`。取不到时按当前节点尺寸算，波纹依然正确，
 * 只是外扩余量少一点。
 */
fun Modifier.monetBackdrop(
    palette: MonetPalette,
    reveal: ThemeReveal?,
    progress: Animatable<Float, AnimationVector1D>,
    activity: Activity? = null,
    coverVisible: Boolean = false,
): Modifier = this then DrawMonetBackdropElement(palette, reveal, progress, activity, coverVisible)

private class DrawMonetBackdropElement(
    private val palette: MonetPalette,
    private val reveal: ThemeReveal?,
    private val progress: Animatable<Float, AnimationVector1D>,
    private val activity: Activity?,
    private val coverVisible: Boolean,
) : ModifierNodeElement<DrawMonetBackdrop>() {

    override fun create() = DrawMonetBackdrop(palette, reveal, progress, activity, coverVisible)

    override fun update(node: DrawMonetBackdrop) {
        node.palette = palette
        node.reveal = reveal
        node.progress = progress
        node.activity = activity
        node.coverVisible = coverVisible
    }

    override fun equals(other: Any?): Boolean =
        other is DrawMonetBackdropElement &&
            other.palette == palette &&
            other.reveal == reveal &&
            other.progress === progress &&
            other.activity === activity &&
            other.coverVisible == coverVisible

    override fun hashCode(): Int {
        var result = palette.hashCode()
        result = 31 * result + (reveal?.hashCode() ?: 0)
        result = 31 * result + progress.hashCode()
        result = 31 * result + (activity?.hashCode() ?: 0)
        result = 31 * result + coverVisible.hashCode()
        return result
    }
}

private class DrawMonetBackdrop(
    var palette: MonetPalette,
    var reveal: ThemeReveal?,
    var progress: Animatable<Float, AnimationVector1D>,
    var activity: Activity?,
    var coverVisible: Boolean,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    // 布局阶段写入、draw 阶段读取，都在主线程，不需要同步
    private val geometry = RevealGeometry()

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        geometry.update(coordinates)
    }

    override fun ContentDrawScope.draw() {
        val overlay = palette.coverScrim(coverVisible)
        if (reveal == null) {
            drawRect(overlay)
            drawContent()
            return
        }
        drawMonetReveal(overlay, reveal!!.from, progress.value)
        drawContent()
    }
}

/** 根背景层：全屏承载扩散，内容压在上面 */
@Composable
fun MonetBackground(
    palette: MonetPalette,
    reveal: ThemeReveal?,
    coverUrl: String? = null,
    onRevealFinished: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val progress = rememberRevealProgress(reveal, onRevealFinished)
    val activity = LocalContext.current.activityOrNull()
    val coverVisible = !coverUrl.isNullOrEmpty()
    Box(modifier.fillMaxSize()) {
        CoverBlurBackdrop(
            coverUrl = coverUrl,
            palette = palette,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .monetBackdrop(palette, reveal, progress, activity, coverVisible),
            content = content,
        )
    }
}
