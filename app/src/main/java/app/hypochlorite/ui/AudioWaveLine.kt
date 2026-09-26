package app.hypochlorite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Decorative waves: PCM energy drives amplitude; the frame clock only moves the curves.
 *
 * [inkColor] defaults to white, which vanishes on a light-mode backdrop (luma 0.88 plus a
 * white scrim). Callers painting on anything but a dark field should pass their theme's ink.
 */
@Composable
fun AudioWaveLine(
    playing: Boolean,
    audioLevel: () -> Float,
    modifier: Modifier = Modifier,
    inkColor: Color = Color.White,
) {
    val readLevel by rememberUpdatedState(audioLevel)
    var amplitude by remember { mutableFloatStateOf(0f) }
    var phase by remember { mutableFloatStateOf(0f) }
    var drift by remember { mutableFloatStateOf(0f) }
    var accent by remember { mutableFloatStateOf(0f) }
    val path = remember { Path() }
    val sparks = remember { ArrayList<WaveSpark>(MaxSparks) }

    LaunchedEffect(playing) {
        var previous = 0L
        var spawnCredit = 0f
        var body = amplitude
        while (playing || amplitude > 0.001f || sparks.isNotEmpty()) {
            withFrameNanos { now ->
                val dt = if (previous == 0L) 1f / 60f else
                    ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
                previous = now
                val raw = readLevel().coerceIn(0f, 1f)
                val target = if (playing) sqrt(raw) else 0f
                val tau = if (target > amplitude) 0.035f else 0.26f
                amplitude += (target - amplitude) * (1f - exp(-dt / tau))
                body += (target - body) * (1f - exp(-dt / 0.32f))
                val attack = if (playing) ((target - body) * 3.5f).coerceIn(0f, 1f) else 0f
                accent += (attack - accent) * (1f - exp(-dt / if (attack > accent) 0.025f else 0.22f))
                val omega = 0.45f + amplitude * WaveSpeed + accent * 1.8f
                phase = (phase + dt * omega) % (2f * PI.toFloat())
                drift = (drift + dt * (0.7f + amplitude * 0.55f)) % (2f * PI.toFloat())

                if (playing && amplitude > 0.04f) {
                    spawnCredit += dt * (2.4f + amplitude * 3.0f)
                    while (spawnCredit >= 1f && sparks.size < MaxSparks) {
                        spawnCredit -= 1f
                        sparks.add(
                            WaveSpark(
                                t = 0f,
                                bob = Random.nextFloat() * (2f * PI.toFloat()),
                                vx = 0.16f + Random.nextFloat() * 0.14f,
                                bobSpeed = 1.1f + Random.nextFloat() * 1.6f,
                                bobAmpDp = 2.2f + Random.nextFloat() * 3.4f,
                                liftDp = (Random.nextFloat() - 0.5f) * 8f,
                                layer = Random.nextInt(3),
                            ),
                        )
                    }
                } else {
                    spawnCredit = 0f
                }
                var i = 0
                while (i < sparks.size) {
                    val spark = sparks[i]
                    spark.t += spark.vx * dt * (0.8f + amplitude * 0.7f + accent * 0.5f)
                    spark.bob += spark.bobSpeed * dt
                    if (spark.t >= 1.02f) sparks.removeAt(i) else i++
                }
            }
        }
        amplitude = 0f
        accent = 0f
        sparks.clear()
    }

    Canvas(modifier.fillMaxWidth().height(30.dp)) {
        val center = size.height / 2f
        val start = 6.dp.toPx()
        val width = (size.width - start).coerceAtLeast(0f)
        val ink = inkColor
        val ampPx = (amplitude * (1f + accent * 0.22f)).coerceAtMost(1f) * size.height
        repeat(3) { layer ->
            path.reset()
            for (step in 0..120) {
                val t = step / 120f
                val x = start + t * width
                val y = center + waveOffset(t, layer, phase, drift, accent) * ampPx
                if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path,
                Brush.horizontalGradient(listOf(
                    ink.copy(alpha = 0.42f - layer * 0.07f),
                    ink.copy(alpha = 0.20f - layer * 0.04f),
                    ink.copy(alpha = 0.025f),
                )),
                style = Stroke(width = 0.65.dp.toPx()),
            )
        }
        val ballR = 1.15.dp.toPx()
        val halfH = size.height / 2f - ballR
        for (spark in sparks) {
            val t = spark.t
            val fade = when {
                t < 0.06f -> t / 0.06f
                t > 0.88f -> ((1f - t) / 0.12f).coerceAtLeast(0f)
                else -> 1f
            }
            val y = (center +
                waveOffset(t.coerceIn(0f, 1f), spark.layer, phase, drift, accent) * ampPx +
                (sin(spark.bob) * spark.bobAmpDp.dp.toPx() * 0.3f +
                    spark.liftDp.dp.toPx() * t * 0.25f) * amplitude
                ).coerceIn(center - halfH, center + halfH)
            drawCircle(
                ink.copy(alpha = 0.42f * fade),
                ballR,
                Offset(start + t * width, y),
            )
        }
        drawCircle(ink.copy(alpha = 0.45f), 4.dp.toPx(), Offset(start, center),
            style = Stroke(0.7.dp.toPx()))
        drawCircle(ink.copy(alpha = 0.30f), 1.5.dp.toPx(), Offset(start, center))
    }
}

private fun waveOffset(t: Float, layer: Int, phase: Float, drift: Float, accent: Float): Float {
    val envelope = sin(t * PI).toFloat()
    val layerPhase = layer * 2.1f
    // Integer phase harmonics keep both wrapped clocks seamless. Each strand
    // stretches independently, while a smaller travelling ripple adds detail.
    val sway = sin(drift + layerPhase)
    val carrier = t * PI.toFloat() * (3f + layer * 0.7f + sway * 0.65f)
    val wave = sin(carrier - phase + layerPhase + sway * 0.55f)
    val ripple = sin(t * PI.toFloat() * (7f + layer) - phase * 2f + layerPhase)
    val breath = 0.84f + 0.16f * sin(drift + t * PI.toFloat() * 2f + layerPhase)
    return (wave * 0.78f + ripple * (0.12f + accent * 0.10f)) *
        envelope * breath * (0.46f - layer * 0.07f)
}

private const val MaxSparks = 14
private const val WaveSpeed = 3.6f

private class WaveSpark(
    var t: Float,
    var bob: Float,
    val vx: Float,
    val bobSpeed: Float,
    val bobAmpDp: Float,
    val liftDp: Float,
    val layer: Int,
)
