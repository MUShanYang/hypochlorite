package app.hypochlorite.player

import app.hypochlorite.netease.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTest {

    @Test
    fun testWrapParentheses() {
        assertEquals("Hello\n(World)", Lyrics.wrapParentheses("Hello (World)"))
        assertEquals("晴天\n（伴奏）", Lyrics.wrapParentheses("晴天（伴奏）"))
        assertEquals("(Intro)", Lyrics.wrapParentheses("(Intro)"))
        assertEquals("七里香\n【Live】", Lyrics.wrapParentheses("七里香【Live】"))
        assertEquals("Hello\n(World)\n(Live)", Lyrics.wrapParentheses("Hello (World) (Live)"))
    }

    @Test
    fun testPureCjkLyric() {
        val split = Lyrics.splitLyric("风吹草动见牛羊")
        assertEquals("风吹草动见牛羊", split.vertical)
        assertNull(split.horizontal)
    }

    @Test
    fun testPureEnglishLyric() {
        val split = Lyrics.splitLyric("Never gonna give you up")
        assertNull(split.vertical)
        assertEquals("Never gonna give you up", split.horizontal)
    }

    @Test
    fun testMixedCjkAndEnglishLyric() {
        val split = Lyrics.splitLyric("爱你 (Love you)")
        assertEquals("爱你", split.vertical)
        assertEquals("Love you", split.horizontal)
    }

    @Test
    fun testMixedCjkAndEnglishWithoutParentheses() {
        val split = Lyrics.splitLyric("说一声 Goodbye 亲爱的")
        assertEquals("说一声 亲爱的", split.vertical)
        assertEquals("Goodbye", split.horizontal)
    }

    @Test
    fun instrumentalPlaceholderIsDropped() {
        assertTrue(Lyrics.isInstrumentalPlaceholder("纯音乐，请欣赏"))
        assertTrue(Lyrics.isInstrumentalPlaceholder("纯音乐,请欣赏"))
        assertTrue(Lyrics.isInstrumentalPlaceholder("  纯音乐，请您欣赏。"))
        assertTrue(Lyrics.isInstrumentalPlaceholder("此歌曲为没有填词的纯音乐，请欣赏"))
        assertFalse(Lyrics.isInstrumentalPlaceholder("纯音乐里的风声"))
        assertFalse(Lyrics.isInstrumentalPlaceholder("请欣赏这首歌"))
        val kept = listOf(
            LyricLine(0, "纯音乐，请欣赏"),
            LyricLine(1000, "风吹过"),
        )
        assertEquals(
            listOf(LyricLine(1000, "风吹过")),
            Lyrics.withoutPlaceholders(kept),
        )
        assertTrue(
            Lyrics.withoutPlaceholders(
                listOf(LyricLine(0, "纯音乐，请欣赏")),
            ).isEmpty(),
        )
    }

    @Test
    fun lyricsToggleDoesNotFlashWhileNextSongLoads() {
        assertTrue(Lyrics.lyricsAvailable(resolved = false, hasLines = false, lastKnown = true))
        assertFalse(Lyrics.lyricsAvailable(resolved = false, hasLines = false, lastKnown = false))
        assertFalse(Lyrics.lyricsAvailable(resolved = true, hasLines = false, lastKnown = true))
        assertTrue(Lyrics.lyricsAvailable(resolved = true, hasLines = true, lastKnown = false))
    }

    @Test
    fun durationLabelScramblesOnSongSwitch() {
        org.junit.Assert.assertTrue(app.hypochlorite.ui.scrambleDurationLabel())
    }

    @Test
    fun edgeRevealDissolvesAtTheLip() {
        assertEquals(0f, Lyrics.edgeReveal(-1f), 0f)
        assertEquals(0f, Lyrics.edgeReveal(0f), 0f)
        assertEquals(1f, Lyrics.edgeReveal(1f), 0f)
        assertEquals(1f, Lyrics.edgeReveal(1.5f), 0f)
        assertEquals(0.75f, Lyrics.edgeReveal(0.5f), 0.01f)
        assertTrue(Lyrics.edgeReveal(0.25f) > 0.25f)
        assertTrue(Lyrics.edgeReveal(0.25f) < Lyrics.edgeReveal(0.5f))
        assertTrue(Lyrics.edgeReveal(0.5f) < Lyrics.edgeReveal(0.75f))
    }

    @Test
    fun sweepRevealSharesTheFillLeadingEdge() {
        assertEquals(0f, Lyrics.sweepReveal(-1f), 0f)
        assertEquals(0f, Lyrics.sweepReveal(-1.01f), 0f)
        assertEquals(1f, Lyrics.sweepReveal(0f), 0f)
        assertEquals(0.5f, Lyrics.sweepReveal(-0.5f), 0f)
        val parent = 400f
        val glyphs = 200f
        val wipe = -0.2f
        val fillEnd = parent * Lyrics.sweepReveal(wipe)
        val oldColorEnd = glyphs * Lyrics.sweepReveal(wipe)
        assertEquals(320f, fillEnd, 0.01f)
        assertEquals(160f, oldColorEnd, 0.01f)
        assertTrue(fillEnd > oldColorEnd)
    }

    @Test
    fun lineHoldMsMatchesCurrentIndexWindow() {
        val lines = listOf(
            LyricLine(0, "a"),
            LyricLine(2_000, "b"),
            LyricLine(2_500, "c"),
        )
        assertEquals(2_000L, Lyrics.lineHoldMs(lines, 0))
        assertEquals(500L, Lyrics.lineHoldMs(lines, 1))
        assertEquals(8_000L, Lyrics.lineHoldMs(lines, 2))
        assertEquals(0L, Lyrics.lineHoldMs(lines, -1))
        assertEquals(0L, Lyrics.lineHoldMs(emptyList(), 0))
    }

    @Test
    fun switchDurationScalesHoldKeepsAuthoredCurveBase() {
        assertEquals(620, Lyrics.switchDurationMs(2_000L, 620))
        assertEquals(400, Lyrics.switchDurationMs(2_000L, 400))
        assertEquals(580, Lyrics.switchDurationMs(2_000L, 580))
        assertEquals(620, Lyrics.switchDurationMs(0L, 620))
        val short = Lyrics.switchDurationMs(800L, 620)
        assertTrue(short < 620)
        assertTrue(short >= 620 * 2 / 5)
        assertEquals(620, Lyrics.switchDurationMs(4_000L, 620))
        assertEquals(620, Lyrics.switchDurationMs(8_000L, 620))
        assertEquals(620, Lyrics.switchDurationMs(20_000L, 620))
        assertEquals(Lyrics.switchDurationMs(8_000L, 620), Lyrics.switchDurationMs(20_000L, 620))
        for (hold in listOf(0L, 500L, 2_000L, 4_000L, 8_000L, 20_000L, 60_000L)) {
            assertTrue(Lyrics.switchDurationMs(hold, 620) <= 620)
            assertTrue(Lyrics.switchDurationMs(hold, 400) <= 400)
            assertTrue(Lyrics.switchDurationMs(hold, 580) <= 580)
        }
    }

    @Test
    fun switchEaseMixSoftensCompressedMotion() {
        assertEquals(1f, Lyrics.switchEaseMix(620, 620), 0f)
        assertEquals(1f, Lyrics.switchEaseMix(800, 620), 0f)
        assertEquals(0f, Lyrics.switchEaseMix(620 * 2 / 5, 620), 0.01f)
        val mid = Lyrics.switchEaseMix(434, 620)
        assertTrue(mid > 0f && mid < 1f)
        val compressed = Lyrics.switchDurationMs(800L, 620)
        assertTrue(Lyrics.switchEaseMix(compressed, 620) < 1f)
    }

    @Test
    fun currentIndexFindsTheLineOnALongTimeline() {
        val lines = List(40) { LyricLine(it * 1_000L, "l$it") }
        assertEquals(-1, Lyrics.currentIndex(lines, -1))
        assertEquals(0, Lyrics.currentIndex(lines, 0))
        assertEquals(0, Lyrics.currentIndex(lines, 999))
        assertEquals(12, Lyrics.currentIndex(lines, 12_500))
        assertEquals(39, Lyrics.currentIndex(lines, 39_000))
        assertEquals(39, Lyrics.currentIndex(lines, 39_000 + 7_999))
        assertEquals(-1, Lyrics.currentIndex(lines, 39_000 + 8_000))
        val tied = listOf(
            LyricLine(0, "a"),
            LyricLine(1_000, "b"),
            LyricLine(1_000, "c"),
            LyricLine(4_000, "d"),
        )
        assertEquals(2, Lyrics.currentIndex(tied, 1_000))
        assertEquals(2, Lyrics.currentIndex(tied, 3_999))
        assertEquals(3, Lyrics.currentIndex(tied, 4_000))
    }

    @Test
    fun scrollTargetDoesNotJumpHomeWhenNoLineIsActive() {
        assertNull(Lyrics.scrollTarget(-1, 12, alreadyFollowing = true))
        assertNull(Lyrics.scrollTarget(-1, 0, alreadyFollowing = false))
        assertEquals(0, Lyrics.scrollTarget(-1, 12, alreadyFollowing = false))
        assertEquals(4, Lyrics.scrollTarget(4, 12, alreadyFollowing = true))
        assertEquals(0, Lyrics.scrollTarget(0, 12, alreadyFollowing = true))
    }

    @Test
    fun testColumnsSplit() {
        val cols = Lyrics.columns("海阔天空 狂风暴雨以后")
        assertTrue(cols.isNotEmpty())
        for (col in cols) {
            assertTrue(col.length <= 8)
        }
    }
}
