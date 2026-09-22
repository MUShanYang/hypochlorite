package app.hypochlorite.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioWaveLevelTest {
    @Test
    fun meteringPreservesPcmAndFollowsLoudness() {
        val processor = AudioLevelProcessor()
        processor.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        processor.flush()

        for (sample in listOf(16384, 4096, 0)) {
            val input = ByteBuffer.allocateDirect(2048).order(ByteOrder.nativeOrder())
            repeat(1024) { input.putShort(sample.toShort()) }
            input.flip()
            processor.queueInput(input)
            assertEquals(0, input.remaining())
            val output = processor.output
            assertEquals(2048, output.remaining())
            repeat(1024) { assertEquals(sample.toShort(), output.short) }
            assertEquals(sample / 32768f, processor.audioLevel(), 0.00001f)
        }
    }

    @Test
    fun flushingClearsPreviousTrackEnergy() {
        val processor = AudioLevelProcessor()
        processor.configure(AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        val input = ByteBuffer.allocateDirect(512).order(ByteOrder.nativeOrder())
        repeat(256) { input.putShort(12000.toShort()) }
        input.flip()
        processor.queueInput(input)
        assertTrue(processor.audioLevel() > 0f)
        processor.flush()
        assertEquals(0f, processor.audioLevel(), 0f)
    }
}
