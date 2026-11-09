package app.podium.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherProtocolTest {

    @Test
    fun `WireSong round-trips between Prism JSON and Podium Track`() {
        val wire = WireSong(
            id = "dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artists = listOf(WireArtistRef("Rick Astley", "UCuAXFkgsw1L7xaCfnd5JJOw")),
            album = WireAlbumRef("Whenever You Need Somebody", "MPREb_12345"),
            durationSec = 213,
            thumbnail = "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
            explicit = false,
        )
        val sanitized = wire.fromPeer()
        assertNotNull(sanitized)
        val track = sanitized!!.toPodiumTrack()
        assertEquals("Never Gonna Give You Up", track.title)
        assertEquals("Rick Astley", track.artistDisplay)
        assertEquals(213_000L, track.durationMs)
        assertEquals("dQw4w9WgXcQ", track.source.providerKey)

        val roundTripped = track.toWireSong()
        assertNotNull(roundTripped)
        assertEquals("dQw4w9WgXcQ", roundTripped!!.id)
        assertEquals("Never Gonna Give You Up", roundTripped.title)
        assertEquals(213, roundTripped.durationSec)
    }

    @Test
    fun `fromPeer rejects invalid video ids and non-https thumbnails`() {
        val badId = WireSong(id = "../etc/passwd", title = "Bad").fromPeer()
        assertNull(badId)

        val httpThumb = WireSong(
            id = "dQw4w9WgXcQ",
            title = "Valid Id",
            thumbnail = "http://evil.local/pic.jpg",
        ).fromPeer()
        assertNotNull(httpThumb)
        assertNull(httpThumb!!.thumbnail)
    }

    @Test
    fun `Wire encodes and decodes Prism protocol messages`() {
        val welcome = Msg.Welcome(
            v = Msg.VERSION,
            hostName = "Podium · Pixel",
            state = Msg.State(
                song = WireSong(id = "dQw4w9WgXcQ", title = "Song A"),
                queue = listOf(WireSong(id = "9bZkp7q19f0", title = "Song B")),
                playing = true,
                positionMs = 42_500L,
                atMs = 100_000L,
                peers = listOf("Galaxy"),
                guestsControl = true,
            ),
        )
        val encoded = Wire.encode(welcome)
        assertTrue(encoded.contains("\"t\":\"welcome\""))

        val decoded = Wire.decode(encoded) as? Msg.Welcome
        assertNotNull(decoded)
        assertEquals("Podium · Pixel", decoded!!.hostName)
        assertEquals("dQw4w9WgXcQ", decoded.state.song?.id)
        assertEquals(42_500L, decoded.state.positionMs)
        assertTrue(decoded.state.guestsControl)
    }

    @Test
    fun `parseHostPort handles bare IP and IP with port`() {
        assertEquals("192.168.1.42" to TogetherSession.PORT, parseHostPort("192.168.1.42"))
        assertEquals("192.168.1.42" to 51234, parseHostPort("192.168.1.42:51234"))
        assertNull(parseHostPort(""))
        assertNull(parseHostPort("192.168.1.42:99999"))
    }
}
