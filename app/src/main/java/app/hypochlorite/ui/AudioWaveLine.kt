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
import app.hypochlorite.ui.theme.LocalHypochloriteColors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Decorative waves: PCM energy drives amplitude; the frame clock only moves the curves. */
@Composable
fun AudioWaveLine(playing: Boolean, audioLevel: () -> Float, modifier: Modifier = Modifier) {
    val colors = LocalHypochloriteColors.current
    val readLevel by rememberUpdatedState(audioLevel)
    var amplitude by remember { mutableFloatStateOf(0f) }
    var phase by remember { mutableFloatStateOf(0f) }
    val path = remember { Path() }
    val sparks = remember { ArrayList<WaveSpark>(MaxSparks) }

    LaunchedEffect(playing) {
        var previous = 0L
        var spawnCredit = 0f
        while (playing || amplitude > 0.001f || sparks.isNotEmpty()) {
            withFrameNanos { now ->
                val dt = if (previous == 0L) 1f / 60f else
                    ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
                previous = now
                val raw = readLevel().coerceIn(0f, 1f)
                val target = if (playing) sqrt(raw) else 0f
                val tau = if (target > amplitude) 0.05f else 0.20f
                amplitude += (target - amplitude) * (1f - exp(-dt / tau))
                val omega = 0.16f + amplitude * WaveSpeed
                phase = (phase + dt * omega) % (2f * PI.toFloat())

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
                            ),
                        )
                    }
                } else {
                    spawnCredit = 0f
                }
                var i = 0
                while (i < sparks.size) {
                    val spark = sparks[i]
                    spark.t += spark.vx * dt
                    spark.bob += spark.bobSpeed * dt
                    if (spark.t >= 1.02f) sparks.removeAt(i) else i++
                }
            }
        }
        amplitude = 0f
        sparks.clear()
    }

    Canvas(modifier.fillMaxWidth().height(30.dp)) {
        val center = size.height / 2f
        val start = 6.dp.toPx()
        val width = (size.width - start).coerceAtLeast(0f)
        val ink = colors.text
        val ampPx = amplitude * size.height
        repeat(3) { layer ->
            path.reset()
            for (step in 0..120) {
                val t = step / 120f
                val x = start + t * width
                val y = center + waveOffset(t, layer, phase) * ampPx
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
                sin(spark.bob) * spark.bobAmpDp.dp.toPx() +
                spark.liftDp.dp.toPx() * t
                ).coerceIn(center - halfH, center + halfH)
            drawCircle(
                Color.White.copy(alpha = 0.42f * fade),
                ballR,
                Offset(start + t * width, y),
            )
        }
        drawCircle(ink.copy(alpha = 0.45f), 4.dp.toPx(), Offset(start, center),
            style = Stroke(0.7.dp.toPx()))
        drawCircle(ink.copy(alpha = 0.30f), 1.5.dp.toPx(), Offset(start, center))
    }
}

private fun waveOffset(t: Float, layer: Int, phase: Float): Float {
    val envelope = sin(t * PI).toFloat()
    val wave = sin(t * PI.toFloat() * (3f + layer * 0.7f) - phase + layer * 2.1f)
    return wave * envelope * (0.42f - layer * 0.07f)
}

private const val MaxSparks = 14
private const val WaveSpeed = 2.4f

private class WaveSpark(
    var t: Float,
    var bob: Float,
    val vx: Float,
    val bobSpeed: Float,
    val bobAmpDp: Float,
    val liftDp: Float,
)
