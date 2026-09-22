package app.hypochlorite.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * 直通电平表，给播放页的波形读。
 *
 * 声音原样搬过去，不改增益、不滤波。不认识的编码也直通，
 * 免得音频轨因为处理器拒绝格式而建不起来。
 */
class AudioLevelProcessor : BaseAudioProcessor() {

    @Volatile private var visualLevel = 0f
    @Volatile private var visualSampleNanos = 0L

    fun audioLevel(): Float =
        if (System.nanoTime() - visualSampleNanos < 250_000_000L) visualLevel else 0f

    private var bypass = false
    private var encoding = C.ENCODING_PCM_16BIT
    private var bytesPerSample = 2

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        encoding = inputAudioFormat.encoding
        bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> {
                bypass = true
                return inputAudioFormat
            }
        }
        bypass = false
        return inputAudioFormat
    }

    override fun onFlush() {
        visualLevel = 0f
        visualSampleNanos = 0L
    }

    override fun onReset() {
        visualLevel = 0f
        visualSampleNanos = 0L
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()
        if (!bypass) measureOutput(out)
    }

    private fun measureOutput(buffer: ByteBuffer) {
        val samples = buffer.remaining() / bytesPerSample
        val stride = (samples / 256).coerceAtLeast(1)
        var energy = 0.0
        var count = 0
        var i = 0
        while (i < samples) {
            val value = readSample(buffer, buffer.position() + i * bytesPerSample)
            if (value.isFinite()) {
                energy += value * value
                count++
            }
            i += stride
        }
        visualLevel = if (count > 0) sqrt(energy / count).toFloat() else 0f
        visualSampleNanos = System.nanoTime()
    }

    private fun readSample(buf: ByteBuffer, bytePos: Int): Float = when (encoding) {
        C.ENCODING_PCM_16BIT -> buf.getShort(bytePos) / 32768f
        C.ENCODING_PCM_24BIT -> {
            val b0 = buf.get(bytePos).toInt() and 0xFF
            val b1 = buf.get(bytePos + 1).toInt() and 0xFF
            val b2 = buf.get(bytePos + 2).toInt()
            ((b2 shl 24) or (b1 shl 16) or (b0 shl 8)) / 2147483648f
        }
        C.ENCODING_PCM_32BIT -> buf.getInt(bytePos) / 2147483648f
        C.ENCODING_PCM_FLOAT -> buf.getFloat(bytePos).coerceIn(-1.5f, 1.5f)
        else -> 0f
    }
}
