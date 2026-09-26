package app.hypochlorite.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMatchPcmTest {

    private fun bytesOf(vararg shorts: Short): ByteArray {
        val out = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            out[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((shorts[i].toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `int16 down to normalized float`() {
        val out = downmixPcmToFloat(bytesOf(0, 8192, (-8192).toShort(), Short.MIN_VALUE), PcmEncoding.PCM_16BIT, 1)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(0.25f, out[1], 1e-6f)
        assertEquals(-0.25f, out[2], 1e-6f)
        assertEquals(-1f, out[3], 1e-6f)
    }

    @Test
    fun `stereo averages both channels`() {
        // 两帧立体声：(1000,3000) 和 (-1000,-3000)
        val out = downmixPcmToFloat(bytesOf(1000, 3000, (-1000).toShort(), (-3000).toShort()), PcmEncoding.PCM_16BIT, 2)
        assertEquals(2, out.size)
        assertEquals(2000 / 32768f, out[0], 1e-6f)
        assertEquals(-2000 / 32768f, out[1], 1e-6f)
    }

    @Test
    fun `float encoding is clamped and little endian`() {
        val raw = ByteArray(8)
        floatArrayOf(2.5f, -0.5f).forEachIndexed { i, v ->
            val bits = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(v).array()
            System.arraycopy(bits, 0, raw, i * 4, 4)
        }
        val out = downmixPcmToFloat(raw, PcmEncoding.PCM_FLOAT, 1)
        assertEquals(1f, out[0], 1e-6f)
        assertEquals(-0.5f, out[1], 1e-6f)
    }

    @Test
    fun `trailing partial frame is dropped`() {
        val out = downmixPcmToFloat(bytesOf(1000, 2000, 3000), PcmEncoding.PCM_16BIT, 2)
        assertEquals(1, out.size)
    }

    @Test
    fun `downmix at equal rate is a copy`() {
        val src = floatArrayOf(0.1f, 0.2f, 0.3f)
        assertTrue(src.contentEquals(resampleLinear(src, 8000, 8000)))
    }

    @Test
    fun `48k to 8k shortens by the rate ratio and preserves a ramp`() {
        val n = 4800
        val src = FloatArray(n) { it / n.toFloat() }
        val out = resampleLinear(src, 48000, 8000)
        assertEquals(n / 6, out.size)
        // 线性斜坡重采样后仍是单调斜坡，且值域不变
        assertEquals(0f, out.first(), 1e-6f)
        assertTrue(out.isSorted())
        assertTrue(out.last() < 1f)
        // 源斜坡每样本涨 1/n，降 6 倍后每目标样本应涨 6/n
        assertEquals(6f / n, out[1] - out[0], 1e-6f)
    }

    @Test
    fun `empty and invalid inputs give empty output`() {
        assertEquals(0, downmixPcmToFloat(ByteArray(0), PcmEncoding.PCM_16BIT, 2).size)
        assertEquals(0, resampleLinear(FloatArray(0), 48000, 8000).size)
        assertEquals(0, resampleLinear(floatArrayOf(1f, 2f), 0, 8000).size)
    }

    private fun FloatArray.isSorted(): Boolean {
        for (i in 1 until size) if (this[i] < this[i - 1]) return false
        return true
    }
}
