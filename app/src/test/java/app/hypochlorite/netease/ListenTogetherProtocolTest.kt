package app.hypochlorite.netease

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherProtocolTest {
    @Test
    fun officialAndTwiceEncodedInvitationsExposeRoomIdentifiers() {
        val invite = ListenInvite(roomId = "123456", inviterId = 7788L)
        assertEquals(invite, parseListenInvite(invite.shareUrl(songId = 42L)))
        assertEquals(
            invite,
            parseListenInvite(
                "orpheus%253A%252F%252FlistenTogether%253FroomId%253D123456%2526inviterId%253D7788",
            ),
        )
        assertEquals(invite, parseListenInvite("123456 7788"))
        assertNull(parseListenInvite("https://music.163.com/song?id=42"))
    }

    @Test
    fun opaqueRoomIdsParseFromShareLinksAndCreateResponses() {
        val roomId = "0f826405fdb26d20100741f54e6d6863_1789994780"
        val invite = parseListenInvite(
            "https://st.music.163.com/listen-together/share/" +
                "?roomId=$roomId&songId=3402236035&inviterId=544390660",
        )
        assertEquals(ListenInvite(roomId, 544390660L), invite)
        assertEquals(
            ListenInvite(roomId, 544390660L),
            parseListenInvite(
                "https://st.music.163.com/listen-together/share/?roomId=" +
                    "0f826405fdb26d20100741f54e6d6863\\_1789994780\\&songId=3402236035\\&inviterId=544390660",
            ),
        )
        assertEquals(
            roomId,
            listenCreatedRoomId(JSONObject("""{"code":200,"data":{"roomInfo":{"roomId":"$roomId"}}}""")),
        )
    }

    @Test
    fun roomStatusKeepsParticipantsAndDetectsAClosedRoom() {
        val active = JSONObject(
            """{
              "code":200,
              "data":{"inRoom":true,"roomInfo":{"roomId":"123","roomUsers":[
                {"userId":"7","nickname":"Lazer","avatarUrl":"https://example/avatar.jpg"}
              ]}}
            }""",
        )
        val status = listenRoomStatus(active)
        assertNotNull(status)
        assertEquals("123", status!!.roomId)
        assertEquals("Lazer", status.participants.single().nickname)
        assertEquals("https://example/avatar.jpg", status.participants.single().avatarUrl)

        val closed = listenRoomStatus(JSONObject("""{"code":200,"data":{"inRoom":false}}"""))
        assertNotNull(closed)
        assertFalse(closed!!.inRoom)
        assertTrue(isListenClosed(JSONObject().put("code", 488)))
    }

    @Test
    fun playlistStateAcceptsObjectAndScalarSongIdentifiers() {
        val response = JSONObject(
            """{
              "code":200,
              "data":{
                "playCommand":{
                  "commandType":"GOTO","progress":"3210","playStatus":"PLAY",
                  "formerSongId":"11","targetSongId":"22","clientSeq":"9"
                },
                "playlist":{
                  "playMode":"ORDER_LOOP",
                  "version":[{"userId":"7","version":"3"}],
                  "displayList":{"result":[{"songId":"11"},"22"]}
                }
              }
            }""",
        )
        val state = listenPlayback(response)
        assertNotNull(state)
        assertEquals(listOf(11L, 22L), state!!.trackIds)
        assertEquals(22L, state.targetSongId)
        assertEquals(3210L, state.progressMillis)
        assertEquals(9L, state.clientSequence)
        assertEquals(3L, state.versions.single().version)
    }

    @Test
    fun versionsMergeByUserAndIncrementTheLocalEditor() {
        val merged = mergeListenVersions(
            listOf(ListenPlaylistVersion(7L, 2L)),
            listOf(ListenPlaylistVersion(7L, 4L), ListenPlaylistVersion(8L, 1L)),
        )
        assertEquals(listOf(ListenPlaylistVersion(7L, 4L), ListenPlaylistVersion(8L, 1L)), merged)
        assertEquals(
            listOf(ListenPlaylistVersion(7L, 5L), ListenPlaylistVersion(8L, 1L)),
            incrementListenVersion(merged, 7L),
        )
        assertEquals(
            merged + ListenPlaylistVersion(9L, 1L),
            incrementListenVersion(merged, 9L),
        )
    }

    @Test
    fun commandPayloadsKeepNestedJsonAsStrings() {
        val command = playCommandInfo(
            commandType = "GOTO",
            progressMillis = 0L,
            playStatus = "PLAY",
            formerSongId = -1L,
            targetSongId = 22L,
            clientSeq = 9L,
        )
        val parsed = JSONObject(command)
        assertEquals("GOTO", parsed.getString("commandType"))
        assertEquals(22L, parsed.getLong("targetSongId"))
        assertEquals(9L, parsed.getLong("clientSeq"))

        val playlist = playlistParam(
            commandType = "REPLACE",
            versions = listOf(ListenPlaylistVersion(7L, 3L)),
            playMode = "ORDER_LOOP",
            displayIds = listOf(11L, 22L),
        )
        val body = JSONObject(playlist)
        assertEquals("REPLACE", body.getString("commandType"))
        assertEquals("ORDER_LOOP", body.getString("playMode"))
        assertEquals("", body.getString("anchorSongId"))
        assertEquals(-1, body.getInt("anchorPosition"))
        assertEquals("11", body.getJSONArray("displayList").getString(0))
        assertEquals(7L, body.getJSONArray("version").getJSONObject(0).getLong("userId"))
    }

    @Test
    fun multiRoomBodyMatchesTheOfficialSeed() {
        val body = multiRoomBody(songId = 42L, playedMillis = 1500L, nextSongIds = listOf(7L, 8L))
        assertEquals("42", body.getString("songId"))
        assertEquals("CREATE", body.getString("from"))
        assertEquals("1500", body.getString("playedTime"))
        assertEquals("[]", body.getString("groupIds"))
        assertEquals("[]", body.getString("inviteUids"))
        assertEquals("[7,8]", body.getString("nextSongIds"))
        assertEquals("", body.getString("checkToken"))
    }

    @Test
    fun queueEditsSyncWhenTheOrderChangesAndDoNotPretendToRestart() {
        assertTrue(listenQueueNeedsSync(true, listOf(1L, 2L), listOf(2L, 1L)))
        assertTrue(listenQueueNeedsSync(true, listOf(1L, 2L, 3L), listOf(1L, 2L)))
        assertFalse(listenQueueNeedsSync(true, listOf(1L, 2L), listOf(1L, 2L)))
        assertFalse(listenQueueNeedsSync(false, listOf(1L), emptyList()))
        assertFalse(listenQueueNeedsSync(true, emptyList(), listOf(1L)))
        assertFalse(listenQueueNeedsSync(true, emptyList(), emptyList()))
        assertEquals("PLAY", listenQueueEditCommand(playing = true))
        assertEquals("PAUSE", listenQueueEditCommand(playing = false))
        assertFalse(listenCanDropQueueTo(inRoom = true, sizeAfter = 0))
        assertTrue(listenCanDropQueueTo(inRoom = true, sizeAfter = 2))
        assertTrue(listenCanDropQueueTo(inRoom = false, sizeAfter = 0))
    }

    @Test
    fun rejectedPayloadIsAFailureEvenWhenCodeIs200() {
        val response = JSONObject("""{"code":200,"data":{"success":"false","message":"房间已满"}}""")
        val error = runCatching { requireListenSuccess(response) }.exceptionOrNull()
        assertNotNull(error)
        assertEquals("房间满员了，进不去", error?.message)
    }
}
