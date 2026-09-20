package app.hypochlorite.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {
    @Test
    fun weapiRoundTripMatchesJsVector() {
        val payload = "{\"ids\":\"[33894312]\",\"br\":128000,\"csrf_token\":\"\"}"
        val secretKey = "a1b2c3d4e5f6g7h8"
        val enc = Crypto.weapiEncrypt(payload, secretKey)
        assertEquals(
            "n625+61rKlkTy06fIvrT1p1eKIBe/CTq4roU/B98anSb2X7bBFB/jOksVrYcMUz/TYUacbqnFHzhumH3YjPMBfvKVLtjcmT3SFxQQthyc+TDiezzvXNjncy93oM09CY1",
            enc.params,
        )
        assertEquals(
            "08bbb6c0901717287ea02154d3b4d649f816043d8ee4c70ff5e9beb888ea9e8372c153f874d0a85a9177e70de9a5ddde79a9ec06116bc327881dad7361f0faf944858c93c5cfa1bd34511975b0e3d7c9a0dafbbdfc0948ccc901135636c8f375f6f4460c238a5a88e225310f331caef53d80bca0694960f6c93439c38307f0b1",
            enc.encSecKey,
        )
        val recovered = Crypto.weapiDecryptParams(enc.params, secretKey)
        assertTrue(recovered.contains("\"ids\":\"[33894312]\""))
        assertTrue(recovered.contains("\"br\":128000"))
    }

    @Test
    fun rsaEncryptMatchesJs() {
        assertEquals(
            "08bbb6c0901717287ea02154d3b4d649f816043d8ee4c70ff5e9beb888ea9e8372c153f874d0a85a9177e70de9a5ddde79a9ec06116bc327881dad7361f0faf944858c93c5cfa1bd34511975b0e3d7c9a0dafbbdfc0948ccc901135636c8f375f6f4460c238a5a88e225310f331caef53d80bca0694960f6c93439c38307f0b1",
            Crypto.rsaEncrypt("a1b2c3d4e5f6g7h8"),
        )
    }

    @Test
    fun eapiRoundTrip() {
        // 端点常量已随直连传输一起移除，这里用一个字面路径做加解密向量即可
        val apiPath = "/api/song/enhance/player/url/v1"
        val json = "{\"ids\":\"[1]\",\"level\":\"jymaster\",\"encodeType\":\"flac\"}"
        val hex = Crypto.eapiEncrypt(apiPath, json)
        assertTrue(hex.matches(Regex("^[0-9A-F]+$")))
        val plain = Crypto.eapiDecrypt(hex)
        assertTrue(plain.contains("jymaster"))
        val parsed = Crypto.eapiDecryptParams(hex)
        assertEquals(apiPath, parsed.url)
        assertTrue(parsed.dataJson!!.contains("jymaster"))
    }

    @Test
    fun parseLrc() {
        val lines = NeteaseClient.parseLrc("[00:01.00]hello\n[00:02.500]world\n{skip}\n")
        assertEquals(2, lines.size)
        assertEquals(1000, lines[0].timeMs)
        assertEquals("hello", lines[0].text)
        assertEquals(2500, lines[1].timeMs)
    }

    @Test
    fun parseLrcMultipleTagsAndCredits() {
        val lines = NeteaseClient.parseLrc(
            "[00:00.00]作词 : someone\n[00:10.00][00:20.00]同一句\n[00:30]竖排\n",
        )
        assertEquals(3, lines.size)
        assertEquals("同一句", lines[0].text)
        assertEquals(10_000, lines[0].timeMs)
        assertEquals(20_000, lines[1].timeMs)
        assertEquals("竖排", lines[2].text)
    }

    @Test
    fun verticalColumnsStayShort() {
        val cols = app.hypochlorite.player.Lyrics.columns("春风十里不如你啊再长一些还要更长", maxPerCol = 5, maxCols = 2)
        assertEquals(2, cols.size)
        assertEquals(5, cols[0].length)
        assertEquals(5, cols[1].length)
        assertEquals(-1, app.hypochlorite.player.Lyrics.currentIndex(emptyList(), 0))
        val timed = listOf(
            app.hypochlorite.netease.LyricLine(0, "a"),
            app.hypochlorite.netease.LyricLine(1000, "b"),
        )
        assertEquals(0, app.hypochlorite.player.Lyrics.currentIndex(timed, 100))
        assertEquals(1, app.hypochlorite.player.Lyrics.currentIndex(timed, 1000))
        assertEquals(-1, app.hypochlorite.player.Lyrics.currentIndex(timed, 10_000))

        // Punctuation clause split when total length exceeds maxPerCol (11)
        val punctCols = app.hypochlorite.player.Lyrics.columns("天青色等烟雨，而我在等你炊烟袅袅升起", maxPerCol = 11, maxCols = 2)
        assertEquals(2, punctCols.size)
        assertEquals("天青色等烟雨", punctCols[0])
        assertEquals("而我在等你炊烟袅袅升起", punctCols[1])

        // Balanced half-split for unpunctuated long text (no 11+1 orphan characters)
        val balancedCols = app.hypochlorite.player.Lyrics.columns("如果我们能够永远在一起呀", maxPerCol = 11, maxCols = 2)
        assertEquals(2, balancedCols.size)
        assertEquals("如果我们能够", balancedCols[0])
        assertEquals("永远在一起呀", balancedCols[1])
    }

    @Test
    fun songIdFromUrl() {
        assertEquals("33894312", SongId.parse("https://music.163.com/#/song?id=33894312"))
        assertEquals("33894312", SongId.parse("https://music.163.com/song/33894312"))
        assertEquals("33894312", SongId.parse("33894312"))
        org.junit.Assert.assertNull(SongId.parse("https://music.163.com/#/playlist?id=123456"))
        org.junit.Assert.assertNull(SongId.parse("https://music.163.com/playlist/123456"))
        org.junit.Assert.assertNull(SongId.parse("https://music.163.com/#/album?id=987"))
        org.junit.Assert.assertNull(SongId.parse(""))
        org.junit.Assert.assertNull(SongId.parse(null))
    }

    @Test
    fun lyricsIsEnglish() {
        assertTrue(app.hypochlorite.player.Lyrics.isEnglish("Never gonna give you up"))
        assertTrue(app.hypochlorite.player.Lyrics.isEnglish("Hello, World! (feat. Artist)"))
        assertTrue(app.hypochlorite.player.Lyrics.isEnglish("Défense d'entrer"))
        org.junit.Assert.assertFalse(app.hypochlorite.player.Lyrics.isEnglish("青花瓷"))
        org.junit.Assert.assertFalse(app.hypochlorite.player.Lyrics.isEnglish("天青色等烟雨 而我在等妳"))
        org.junit.Assert.assertFalse(app.hypochlorite.player.Lyrics.isEnglish("你是我的 baby"))
        org.junit.Assert.assertFalse(app.hypochlorite.player.Lyrics.isEnglish("さくら"))
        org.junit.Assert.assertFalse(app.hypochlorite.player.Lyrics.isEnglish("사랑해"))
        org.junit.Assert.assertFalse(app.hypochlorite.player.Lyrics.isEnglish(""))
        org.junit.Assert.assertFalse(app.hypochlorite.player.Lyrics.isEnglish("12345"))
    }
}
