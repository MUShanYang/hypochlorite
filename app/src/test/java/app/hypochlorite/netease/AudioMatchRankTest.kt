package app.hypochlorite.netease

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMatchRankTest {

    private fun hit(id: String, name: String = "song") =
        AudioMatchHit(Song(id = id, name = name), startTimeMs = 0)

    private fun song(
        id: String,
        pc: Boolean = false,
        albumName: String = "正规专辑",
        albumId: Long = 100,
        copyrightId: Long = 10,
        st: Int = 0,
        fee: Int = 8,
    ): JSONObject {
        val al = JSONObject().put("id", albumId).put("name", albumName)
        val ar = org.json.JSONArray().put(JSONObject().put("name", "Artist"))
        val obj = JSONObject()
            .put("id", id.toLong())
            .put("name", "n$id")
            .put("al", al)
            .put("ar", ar)
            .put("fee", fee)
            .put("copyrightId", copyrightId)
            .put("privilege", JSONObject().put("id", id.toLong()).put("st", st).put("fee", fee))
        if (pc) obj.put("pc", JSONObject().put("nickname", "uploader"))
        return obj
    }

    @Test
    fun `private cloud upload scores below official catalog track`() {
        val cloud = audioMatchOfficialScore(song("1", pc = true, copyrightId = 0))
        val official = audioMatchOfficialScore(song("2", pc = false))
        assertTrue(official > cloud)
    }

    @Test
    fun `preferOfficial puts official ahead of cloud copy keeping relative order among equals`() {
        val hits = listOf(hit("11"), hit("22"), hit("33"))
        val json = mapOf(
            "11" to song("11", pc = true, copyrightId = 0, albumName = "某人的云盘"),
            "22" to song("22"),
            "33" to song("33"),
        )
        val ranked = preferOfficialAudioMatchHits(hits, json)
        assertEquals(listOf("22", "33", "11"), ranked.map { it.song.id })
    }

    @Test
    fun `single hit passes through unchanged`() {
        val hits = listOf(hit("99"))
        assertEquals(hits, preferOfficialAudioMatchHits(hits, mapOf("99" to song("99", pc = true))))
    }

    @Test
    fun `no-copyright privilege is demoted`() {
        val dead = audioMatchOfficialScore(song("1", st = -200))
        val live = audioMatchOfficialScore(song("2", st = 0))
        assertTrue(live > dead)
    }
}
