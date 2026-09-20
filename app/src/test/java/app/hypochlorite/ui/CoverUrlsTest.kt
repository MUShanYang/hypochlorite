package app.hypochlorite.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverUrlsTest {

    @Test
    fun sizedAppendsParamOnBareNeteaseUrl() {
        assertEquals(
            "https://p1.music.126.net/abc.jpg?param=400y400",
            CoverUrls.sized("https://p1.music.126.net/abc.jpg", 400),
        )
    }

    @Test
    fun sizedReplacesExistingParam() {
        assertEquals(
            "https://p2.music.126.net/abc.jpg?param=400y400",
            CoverUrls.sized("https://p2.music.126.net/abc.jpg?param=140y140", 400),
        )
    }

    @Test
    fun sizedKeepsOtherQueryParams() {
        assertEquals(
            "https://p1.music.126.net/abc.jpg?foo=1&param=200y200",
            CoverUrls.sized("https://p1.music.126.net/abc.jpg?foo=1&param=140y140", 200),
        )
        assertEquals(
            "https://p1.music.126.net/abc.jpg?foo=1&bar=2&param=200y200",
            CoverUrls.sized("https://p1.music.126.net/abc.jpg?param=140y140&foo=1&bar=2", 200),
        )
    }

    @Test
    fun sizedLeavesForeignCdnAlone() {
        val other = "https://cdn.example.com/cover.jpg?w=500"
        assertEquals(other, CoverUrls.sized(other, 400))
    }

    @Test
    fun sizedIgnoresEmptyAndNonPositive() {
        assertEquals("", CoverUrls.sized("", 400))
        assertEquals("https://p1.music.126.net/a.jpg", CoverUrls.sized("https://p1.music.126.net/a.jpg", 0))
    }

    @Test
    fun isNeteaseCdnDetectsHosts() {
        assertTrue(CoverUrls.isNeteaseCdn("https://p1.music.126.net/a.jpg"))
        assertTrue(CoverUrls.isNeteaseCdn("http://music.163.com/api/img"))
        assertFalse(CoverUrls.isNeteaseCdn("https://example.com/music.126.net/fake.jpg"))
    }
}
