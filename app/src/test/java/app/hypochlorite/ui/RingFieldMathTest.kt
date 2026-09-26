package app.hypochlorite.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class RingFieldMathTest {

    @Test
    fun `ring radius grows fast then slow and spans the field`() {
        assertEquals(0f, ringRadiusNorm(0f), 1e-6f)
        assertEquals(1f, ringRadiusNorm(1f), 1e-6f)
        // sqrt 的前快后慢：一半行程时已经超过一半半径
        assertTrue(ringRadiusNorm(0.5f) > 0.7f)
        var previous = -1f
        for (i in 0..100) {
            val v = ringRadiusNorm(i / 100f)
            assertTrue("radius must be monotonic at $i", v >= previous)
            previous = v
        }
        assertEquals(0f, ringRadiusNorm(-2f), 1e-6f)
        assertEquals(1f, ringRadiusNorm(9f), 1e-6f)
    }

    @Test
    fun `ring fades out to nothing at the rim`() {
        assertEquals(0f, ringAlpha(1f), 1e-6f)
        assertTrue(ringAlpha(0f) > ringAlpha(0.5f))
        assertTrue(ringAlpha(0.5f) > ringAlpha(0.9f))
        var previous = Float.MAX_VALUE
        for (i in 0..50) {
            val v = ringAlpha(i / 50f)
            assertTrue("alpha must fall monotonically at $i", v <= previous + 1e-6f)
            previous = v
        }
    }

    @Test
    fun `ring width thins with distance but stays positive`() {
        assertEquals(1f, ringWidth(0f), 1e-6f)
        assertEquals(0.4f, ringWidth(1f), 1e-6f)
        assertTrue(ringWidth(1f) > 0f)
    }

    @Test
    fun `slots are evenly distributed and wrap within one turn`() {
        val phases = (0 until RingSlots).map { ringPhase(0.31f, it) }
        assertTrue(phases.all { it >= 0f && it < 1f })
        assertEquals(RingSlots, phases.distinct().size)
        // 相邻槽位的间距恒定
        val sorted = phases.sorted()
        val gaps = sorted.zipWithNext { a, b -> b - a }
        for (g in gaps) assertEquals(1f / RingSlots, g, 1e-5f)
    }

    @Test
    fun `balls ride their ring — angle and radius agree with the slot`() {
        for (clock in listOf(0f, 0.17f, 0.63f, 0.94f)) {
            for (j in 0 until RingBalls) {
                val slot = ballSlot(j)
                val p = ringPhase(clock, slot)
                val r = ringRadiusNorm(p)
                val theta = ballPhase(clock, j) * 2f * PI.toFloat()
                // 球的极坐标半径必须等于它所骑那一环的半径，误差只来自 Float 精度
                val ballR = hypot(r * cos(theta), r * sin(theta))
                assertEquals(r, ballR, 1e-6f)
            }
        }
    }

    @Test
    fun `balls do not share a slot`() {
        val slots = (0 until RingBalls).map { ballSlot(it) }
        assertEquals(RingBalls, slots.distinct().size)
        assertTrue(slots.all { it in 0 until RingSlots })
    }

    @Test
    fun `colour wave peaks on the front and decays outward`() {
        assertEquals(1f, waveBoost(0.5f, 0.5f), 1e-6f)
        val near = waveBoost(0.6f, 0.5f)
        val far = waveBoost(0.8f, 0.5f)
        assertTrue(near > far)
        assertTrue(far > 0f)
        // sigma 越小波前越窄
        assertTrue(waveBoost(0.65f, 0.5f, sigma = 0.05f) < waveBoost(0.65f, 0.5f, sigma = 0.15f))
        assertEquals(0f, waveBoost(0.5f, 0.5f, sigma = 0f), 1e-6f)
    }

    @Test
    fun `collapse starts from the inner rings and finishes outer last`() {
        // 外圈刚起步（uv=0.42）时内圈已收掉七成；内圈在 uv=0.58 收完，
        // 也就是"内圈收尾"和"外圈起步"有 0.16 的重叠，链式收拢而不是逐条闸门
        assertEquals(1f, collapseSlotUv(1f, 0), 1e-6f)
        assertEquals(0f, collapseSlotUv(CollapseStagger, RingSlots - 1), 1e-6f)
        assertTrue(collapseSlotUv(CollapseStagger, 0) > 0.7f)
        assertEquals(1f, collapseSlotUv(CollapseStagger + (1f - CollapseStagger), 0), 1e-6f)
        assertTrue(collapseSlotUv(0.58f, RingSlots - 1) > 0f)
        assertTrue(collapseSlotUv(0.5f, 0) > collapseSlotUv(0.5f, RingSlots - 1))
        var previousInner = -1f
        var previousOuter = -1f
        for (i in 0..100) {
            val uv = i / 100f
            val inner = collapseSlotUv(uv, 0)
            val outer = collapseSlotUv(uv, RingSlots - 1)
            assertTrue(inner >= previousInner - 1e-6f)
            assertTrue(outer >= previousOuter - 1e-6f)
            previousInner = inner
            previousOuter = outer
        }
    }

    @Test
    fun `collapse gathers inward without overshooting the floor`() {
        val live = ringRadiusNorm(0.8f)
        assertEquals(live, collapseRadius(live, 0f), 1e-6f)
        val floor = live * CollapseFloorRatio
        assertEquals(floor, collapseRadius(live, 1f, floor), 1e-6f)
        var previous = live + 1e-6f
        for (i in 0..100) {
            val r = collapseRadius(live, i / 100f, floor)
            assertTrue("collapse must be monotonic inward at $i", r <= previous + 1e-6f)
            assertTrue("collapse must not pass the floor at $i", r >= floor - 1e-6f)
            previous = r
        }
    }

    @Test
    fun `collapse alpha fades from full to none`() {
        assertEquals(1f, collapseAlpha(0f), 1e-6f)
        assertEquals(0f, collapseAlpha(1f), 1e-6f)
        assertTrue(collapseAlpha(0.4f) > collapseAlpha(0.9f))
    }
}
