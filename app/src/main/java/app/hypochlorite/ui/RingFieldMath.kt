package app.hypochlorite.ui

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Ring field math: pure functions behind the audio-recognition radar.
 *
 * Kept free of Compose types so the geometry can be unit-tested on the JVM —
 * everything here returns normalised units (0..1 of the field radius) and the
 * caller converts to pixels.
 */

/** Ring slots on screen at once. Nine full circles are enough for the "endless rings" illusion. */
const val RingSlots = 9

/** Balls riding the rings; slot binding is [ballSlot] so they never share a ring. */
const val RingBalls = 3

/** Fraction of the total collapse during which the outer rings are still stationary. */
const val CollapseStagger = 0.42f

/**
 * Where a collapsed ring stops: a fraction of its **own** live radius, not zero and not a
 * shared absolute. Callers pass `floor = liveRadius * CollapseFloorRatio` per ring (see
 * [collapseRadius]'s default of `0f`), which is what `RingFieldMathTest` pins down — it makes
 * the whole field contract by the same ratio instead of sending outer rings on a longer errand.
 */
const val CollapseFloorRatio = 0.34f

/** Radius growth inside one slot's life: sqrt means fast out, slow down — dense core, sparse rim. */
fun ringRadiusNorm(p: Float): Float = sqrt(p.coerceIn(0f, 1f))

/** A ring fades out quadratically as it travels outward; fully transparent at the rim. */
fun ringAlpha(p: Float): Float {
    val q = (1f - p.coerceIn(0f, 1f))
    return q * q * 0.5f
}

/** Stroke thins slightly with distance so far rings read as thinner, not just fainter. */
fun ringWidth(p: Float): Float = 1f - p.coerceIn(0f, 1f) * 0.6f

/**
 * Continuous position of slot [slot] at clock [clock].
 *
 * Speed is deliberately irrational-ish (not a divisor of the slot count) so rings
 * never visibly re-sync into one clump.
 */
fun ringPhase(clock: Float, slot: Int, slots: Int = RingSlots): Float =
    (clock + slot.toFloat() / slots) % 1f

/**
 * Normalised angular position of ball [index] at spin [clock].
 *
 * The ball is placed on its ring by angle only — its radius always comes from
 * [ringRadiusNorm] of the same slot phase, which is what keeps it riding the line.
 */
fun ballPhase(clock: Float, index: Int, balls: Int = RingBalls): Float =
    (clock + index.toFloat() / balls) % 1f

/** Slot a ball rides. Multiplier must be coprime-ish with the ball count so pairs don't collide. */
fun ballSlot(index: Int, slots: Int = RingSlots, balls: Int = RingBalls): Int =
    (index * (slots / balls)) % slots

/**
 * Gaussian boost of the outward colour wave: 1 on the wavefront, decaying with
 * radial distance. A thin [sigma] makes a sharp sweep instead of a global flash.
 */
fun waveBoost(radiusNorm: Float, waveNorm: Float, sigma: Float = 0.09f): Float {
    if (sigma <= 0f) return 0f
    val d = radiusNorm - waveNorm
    return exp(-(d * d) / (2f * sigma * sigma))
}

/**
 * Collapse stagger: how far slot [slot] has travelled through the inward gather.
 *
 * The innermost ring starts immediately and the outermost starts at exactly
 * [CollapseStagger] of the gesture, so the field reads as a chain being reeled
 * in from the middle — not a shutter.
 */
fun collapseSlotUv(uv: Float, slot: Int, slots: Int = RingSlots): Float {
    val last = (slots - 1).coerceAtLeast(1)
    val start = slot.toFloat() / last * CollapseStagger
    return ((uv - start) / (1f - CollapseStagger)).coerceIn(0f, 1f)
}

/** Ring radius during collapse, from its live travel radius down to the gathering floor. */
fun collapseRadius(progressRadius: Float, slotUv: Float, floor: Float = 0f): Float {
    val u = slotUv.coerceIn(0f, 1f)
    val ease = u * u * (3f - 2f * u)
    return progressRadius + (floor - progressRadius) * ease
}

/** Collapse dims rings as they gather; multiplied onto [ringAlpha]. */
fun collapseAlpha(slotUv: Float): Float {
    val u = slotUv.coerceIn(0f, 1f)
    return 1f - u * u
}
