package app.hypochlorite.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutTest {

    @Test
    fun usbExclusiveToggleDoesNotRebuildPipeline() {
        val off = AudioOutConfig(usbExclusive = false)
        val on = AudioOutConfig(usbExclusive = true)
        assertTrue(off.samePipelineAs(on))
        assertTrue(on.samePipelineAs(off))
    }

    @Test
    fun gainAndDirectVolumeDoNotRebuildPipeline() {
        val base = AudioOutConfig()
        assertTrue(base.samePipelineAs(base.copy(gainPercent = 40)))
        assertTrue(base.samePipelineAs(base.copy(directVolume = true)))
        assertTrue(base.copy(gainPercent = 40).samePipelineAs(base.copy(gainPercent = 0)))
    }

    @Test
    fun exclusiveFocusToggleStillRebuildsPipeline() {
        val off = AudioOutConfig(exclusiveFocus = false)
        val on = AudioOutConfig(exclusiveFocus = true)
        assertFalse(off.samePipelineAs(on))
    }

    @Test
    fun zeroGainIsMuteMatchingUiLabel() {
        assertEquals(0f, gainForPercent(0), 0f)
        assertEquals(0f, gainForPercent(-3), 0f)
        assertTrue(gainForPercent(GAIN_MUTE_BELOW) > 0f)
        assertEquals(1f, gainForPercent(93), 0f)
        assertEquals(1f, gainForPercent(100), 0f)
        assertTrue(gainDbForPercent(0).isInfinite() && gainDbForPercent(0) < 0)
    }

    @Test
    fun rememberedIdUnsetIsPresentWhenAnyUsbExists() {
        assertTrue(rememberedUsbStillPresent(-1, listOf(7)))
        assertFalse(rememberedUsbStillPresent(-1, emptyList()))
    }

    @Test
    fun rememberedIdGoneWhenMissingFromPresentSet() {
        assertFalse(rememberedUsbStillPresent(3, listOf(7, 9)))
        assertTrue(rememberedUsbStillPresent(7, listOf(7, 9)))
        assertFalse(rememberedUsbStillPresent(7, emptyList()))
    }
}
