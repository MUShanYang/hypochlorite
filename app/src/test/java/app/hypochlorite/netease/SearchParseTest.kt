package app.hypochlorite.netease

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchParseTest {
    @Test
    fun artistSearchReadsAliasCountsAndFallsBackToImg1v1() {
        val json = JSONObject(
            """
            {
              "code": 200,
              "result": {
                "artistCount": 12,
                "artists": [
                  {
                    "id": 10559,
                    "name": "孙燕姿",
                    "picUrl": "",
                    "img1v1Url": "https://example/sun.jpg",
                    "alias": ["Stefanie Sun", "Stefanie"],
                    "musicSize": 606,
                    "albumSize": 41
                  },
                  {"id": 0, "name": "丢掉"}
                ]
              }
            }
            """.trimIndent(),
        )
        val page = parseArtistSearch(json, limit = 10)
        assertEquals(12, page.total)
        assertEquals(1, page.items.size)
        val artist = page.items.single()
        assertEquals("10559", artist.id)
        assertEquals("孙燕姿", artist.name)
        assertEquals("https://example/sun.jpg", artist.cover)
        assertEquals("Stefanie Sun", artist.alias)
        assertEquals(606, artist.musicSize)
        assertEquals(41, artist.albumSize)
    }

    @Test
    fun albumAndPlaylistSearchKeepTheFieldsRowsNeed() {
        val albums = parseAlbumSearch(
            JSONObject(
                """
                {
                  "result": {
                    "albumCount": 2,
                    "albums": [
                      {
                        "id": 9,
                        "name": "未完成",
                        "picUrl": "https://example/album.jpg",
                        "size": 11,
                        "publishTime": 1041379200000,
                        "artist": {"id": 10559, "name": "孙燕姿"}
                      },
                      {"id": 10, "name": "另一张"}
                    ]
                  }
                }
                """.trimIndent(),
            ),
            limit = 1,
        )
        assertEquals(2, albums.total)
        assertEquals(1, albums.items.size)
        assertEquals("未完成", albums.items[0].name)
        assertEquals("孙燕姿", albums.items[0].artistName)
        assertEquals("10559", albums.items[0].artistId)
        assertEquals(11, albums.items[0].songCount)
        assertEquals(1041379200000L, albums.items[0].publishTime)

        val playlists = parsePlaylistSearch(
            JSONObject(
                """
                {
                  "result": {
                    "playlists": [
                      {
                        "id": 77,
                        "name": "我不难过",
                        "coverImgUrl": "https://example/pl.jpg",
                        "trackCount": 36,
                        "creator": {"userId": 5, "nickname": "燕姿"}
                      }
                    ]
                  }
                }
                """.trimIndent(),
            ),
            limit = 20,
        )
        assertEquals(-1, playlists.total)
        val playlist = playlists.items.single()
        assertEquals("77", playlist.id)
        assertEquals(36, playlist.trackCount)
        assertEquals("燕姿", playlist.creatorName)
        assertEquals("5", playlist.creatorId)
    }

    @Test
    fun missingResultIsAnEmptyPage() {
        val page = parseArtistSearch(JSONObject("""{"code":200}"""), limit = 20)
        assertTrue(page.items.isEmpty())
        assertEquals(0, page.total)
    }
}
