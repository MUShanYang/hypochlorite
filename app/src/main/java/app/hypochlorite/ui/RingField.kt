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
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * 识曲页的同心环场。满帧驱动的单时钟：`clock` 推进环从中心向外走，`spin` 推着球绕圈。
 *
 * 几何全在 [RingFieldMath]（纯函数、已单测），这里只负责「把时钟喂进去 + 画」。
 * - idle（[active]=false）：只一颗慢呼吸环，表示「待命」。
 * - listening（[active]=true）：[RingSlots] 道环错相外扩、越远越淡，[RingBalls] 颗球压在环上公转。
 *
 * [energy] 是 0..1 的实时电平（app 自己的播放器），只用来轻微加速环与球（accent 包络，
 * 抄 [AudioWaveLine] 的不对称 tau）——没声音时基础速度仍保证画面在动。
 */
@Composable
fun RingField(
    active: Boolean,
    modifier: Modifier = Modifier,
    energy: () -> Float = { 0f },
    ringColor: Color = Color.White,
    ballColor: Color = Color.White,
) {
    val readEnergy by rememberUpdatedState(energy)
    var clock by remember { mutableFloatStateOf(0f) }
    var spin by remember { mutableFloatStateOf(0f) }
    var breath by remember { mutableFloatStateOf(0f) }
    var accent by remember { mutableFloatStateOf(0f) }

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

        if (active) {
            for (i in 0 until RingSlots) {
                val p = ringPhase(clock, i)
                val r = coreR + ringRadiusNorm(p) * span
                val a = ringAlpha(p)
                if (a <= 0.002f) continue
                drawCircle(
                    color = ringColor.copy(alpha = a),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = strokePx * ringWidth(p)),
                )
            }
            for (j in 0 until RingBalls) {
                val slot = ballSlot(j)
                val p = ringPhase(clock, slot)
                val r = coreR + ringRadiusNorm(p) * span
                val theta = ballPhase(spin, j) * 2f * PI.toFloat()
                drawCircle(
                    color = ballColor.copy(alpha = (ringAlpha(p) + 0.25f).coerceAtMost(0.85f)),
                    radius = ballR,
                    center = Offset(cx + r * cos(theta), cy + r * sin(theta)),
                )
            }
        } else {
            // 待命：一颗从 core 轻微起伏的呼吸环，暗示「这里可以点」
            val pulse = 0.5f + 0.5f * sin(breath * 2f * PI.toFloat())
            val r = coreR * (1f + 0.06f * pulse)
            drawCircle(
                color = ringColor.copy(alpha = 0.10f + 0.12f * pulse),
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = strokePx),
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
