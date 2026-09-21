package app.hypochlorite.ui

import app.hypochlorite.ui.theme.MonetPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverBlurBackdropTest {

    @Test
    fun scrimIsOpaqueWithoutCover() {
        val dark = MonetPalette.MonoDark.coverScrim(coverVisible = false)
        assertEquals(MonetPalette.MonoDark.background, dark)
        val light = MonetPalette.MonoLight.coverScrim(coverVisible = false)
        assertEquals(MonetPalette.MonoLight.background, light)
    }

    @Test
    fun scrimKeepsHueAndDropsAlphaWhenCoverShows() {
        val dark = MonetPalette.MonoDark.coverScrim(coverVisible = true)
        assertEquals(MonetPalette.MonoDark.background.red, dark.red)
        assertEquals(MonetPalette.MonoDark.background.green, dark.green)
        assertEquals(MonetPalette.MonoDark.background.blue, dark.blue)
        assertTrue(dark.alpha in 0.5f..0.7f)

        val light = MonetPalette.MonoLight.coverScrim(coverVisible = true)
        assertEquals(1f, light.red, 0.001f)
        assertTrue(light.alpha in 0.65f..0.85f)
        assertTrue(light.alpha > dark.alpha)
    }
}
