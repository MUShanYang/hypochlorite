package app.hypochlorite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** [RingField] 的 `wave` 不画波时传的哨兵值。 */
const val NoRingWave = -1f

/**
 * 识曲页的同心环场。满帧驱动的单时钟：`clock` 推进环从中心向外走，`spin` 推着球绕圈。
 *
 * 几何全在 [RingFieldMath]（纯函数、已单测），这里只负责「把时钟喂进去 + 画」。
 * - idle（都不满足）：只一颗慢呼吸环，表示「待命」。
 * - listening（[active]）：[RingSlots] 道环错相外扩、越远越淡，[RingBalls] 颗球压在环上公转。
 * - gathering（[gather] 在 0..1 之间）：同一批环被从中间往里收，[RingSlots] 道错峰 ——
 *   内圈先动、外圈后动，读起来像一条链被收进来，不是闸门落下。
 *
 * 优先级是 **gathering > active > idle**：命中那一刻 [active] 就翻回 false 了，若还按
 * 「active 否则呼吸」二分，收拢根本来不及被看见。
 *
 * [energy] 是 0..1 的实时电平，只用来轻微加速环与球（accent 包络，抄 [AudioWaveLine] 的
 * 不对称 tau）——没声音时基础速度仍保证画面在动。识别页喂的是**抓来的系统音频**电平，
 * 不是自家播放器（识别时自家是暂停的）。
 *
 * [wave] 与 [gather] 都是驱动值，由调用方的 `Animatable` 提供，**只在绘制阶段读**：
 * 逐帧只脏这块 Canvas，不重组整页。所以别把它们 hoist 到 composable 体里的局部变量。
 * 时钟也不因 [active] 变化而停：命中瞬间环仍在走，同时被往里收，两层运动叠在一起。
 */
@Composable
fun RingField(
    active: Boolean,
    modifier: Modifier = Modifier,
    energy: () -> Float = { 0f },
    ringColor: Color = Color.White,
    ballColor: Color = Color.White,
    waveColor: Color = ringColor,
    wave: () -> Float = { NoRingWave },
    gather: () -> Float = { 0f },
) {
    val readEnergy by rememberUpdatedState(energy)
    var clock by remember { mutableFloatStateOf(0f) }
    var spin by remember { mutableFloatStateOf(0f) }
    var breath by remember { mutableFloatStateOf(0f) }
    var accent by remember { mutableFloatStateOf(0f) }
    // 每环本帧的半径与 alpha。绘制阶段自己写自己读，攒一次省掉球循环里的重复求值。
    val radii = remember { FloatArray(RingSlots) }
    val alphas = remember { FloatArray(RingSlots) }

    LaunchedEffect(active) {
        var previous = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (previous == 0L) 1f / 60f else ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
                previous = now
                val raw = readEnergy().coerceIn(0f, 1f)
                val target = if (active) raw else 0f
                accent += (target - accent) * (1f - exp(-dt / if (target > accent) 0.05f else 0.3f))
                val speed = RingBaseSpeed + accent * RingAccentSpeed
                clock = (clock + dt * speed) % 1f
                spin = (spin + dt * (SpinBaseSpeed + accent * SpinAccentSpeed)) % 1f
                breath = (breath + dt * BreathSpeed) % 1f
            }
        }
    }

    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val fieldR = minOf(cx, cy)
        val coreR = fieldR * CoreRatio
        val span = fieldR * OuterRatio - coreR
        val strokePx = 1.5.dp.toPx()
        val ballR = 2.2.dp.toPx()

        val g = gather().coerceIn(0f, 1f)
        val gathering = g > GatherEpsilon && g < 1f - GatherEpsilon
        val waveNorm = wave()

        if (!active && !gathering) {
            // 待命：一颗从 core 轻微起伏的呼吸环，暗示「这里可以点」
            val pulse = 0.5f + 0.5f * sin(breath * 2f * PI.toFloat())
            val r = coreR * (1f + 0.06f * pulse)
            drawCircle(
                color = ringColor.copy(alpha = 0.10f + 0.12f * pulse),
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = strokePx),
            )
            return@Canvas
        }

        for (i in 0 until RingSlots) {
            val p = ringPhase(clock, i)
            val rn = ringRadiusNorm(p)
            var r = coreR + rn * span
            var a = ringAlpha(p)
            var width = strokePx * ringWidth(p)
            if (gathering) {
                val uv = collapseSlotUv(g, i)
                // floor 按**本环自己**的活动半径取比例（口径同 RingFieldMathTest），
                // 于是整片场按同一比例缩，而不是外圈孤零零地跑得比谁都快。
                r = collapseRadius(r, uv, floor = r * CollapseFloorRatio)
                a *= collapseAlpha(uv)
                width *= 1f - uv * 0.4f
            }
            radii[i] = r
            alphas[i] = a
            // 命中那一下的径向颜色波：谁被波前扫到，谁变色、加粗、提亮
            val boost = if (waveNorm >= 0f) waveBoost(rn, waveNorm) else 0f
            if (a + boost <= 0.002f) continue
            val color = if (boost > 0.01f) lerp(ringColor, waveColor, boost) else ringColor
            drawCircle(
                color = color.copy(alpha = (a + boost * 0.45f).coerceAtMost(1f)),
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = width * (1f + boost * 1.6f)),
            )
        }
        for (j in 0 until RingBalls) {
            val slot = ballSlot(j)
            val theta = ballPhase(spin, j) * 2f * PI.toFloat()
            // 0.25 是「球比它骑的环亮一点」的偏置；收拢时得跟着场一起淡掉，
            // 否则环已经看不见了却还剩三颗点。
            val a = (alphas[slot] + 0.25f * (1f - if (gathering) g else 0f)).coerceAtMost(0.85f)
            drawCircle(
                color = ballColor.copy(alpha = a),
                radius = ballR,
                center = Offset(cx + radii[slot] * cos(theta), cy + radii[slot] * sin(theta)),
            )
        }
    }
}

private const val CoreRatio = 0.53f
private const val OuterRatio = 0.99f
private const val RingBaseSpeed = 0.22f
private const val RingAccentSpeed = 0.5f
private const val SpinBaseSpeed = 0.16f
private const val SpinAccentSpeed = 0.4f
private const val BreathSpeed = 0.4f
private const val GatherEpsilon = 0.001f
