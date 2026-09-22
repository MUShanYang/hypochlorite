package app.hypochlorite.player

import app.hypochlorite.netease.LyricLine
import app.hypochlorite.netease.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSnapshotTest {

    @Test
    fun clockTakesProgressFields() {
        val snap = PlayerSnapshot(
            positionMs = 12_000,
            durationMs = 180_000,
            lyricIndex = 4,
            playing = true,
        )
        assertEquals(PlayerClock(12_000, 180_000, 4), snap.clock())
    }

    @Test
    fun sameUiAsIgnoresProgressAndLyricIndex() {
        val song = Song("1", "a")
        val queue = listOf(song)
        val lines = listOf(LyricLine(0, "hi"))
        val a = PlayerSnapshot(
            queue = queue,
            index = 0,
            current = song,
            playing = true,
            positionMs = 100,
            durationMs = 2000,
            lyricLines = lines,
            lyricIndex = 0,
        )
        val b = a.copy(positionMs = 900, durationMs = 2100, lyricIndex = 1)
        assertTrue(a.sameUiAs(b))
        assertFalse(a.sameUiAs(b.copy(playing = false)))
        assertFalse(a.sameUiAs(b.copy(index = 1)))
    }

    @Test
    fun sameUiAsSeesNewLyricDocument() {
        val a = PlayerSnapshot(lyricLines = listOf(LyricLine(0, "a")))
        val b = PlayerSnapshot(lyricLines = listOf(LyricLine(0, "a")))
        assertFalse(a.sameUiAs(b))
        assertTrue(a.sameUiAs(a.copy(positionMs = 50)))
    }
}
