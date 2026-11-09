package app.podium.together

import app.podium.core.model.AlbumId
import app.podium.core.model.AlbumRef
import app.podium.core.model.ArtistCredit
import app.podium.core.model.ArtistId
import app.podium.core.model.ArtworkRef
import app.podium.core.model.Explicitness
import app.podium.core.model.MediaKind
import app.podium.core.model.PlaybackRoute
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.sources.youtubemusic.YouTubeMusicSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.Socket
import java.net.URI

/**
 * Wire representation of an artist credit on a Listen Together session. Matches Prism's `ArtistRef`
 * JSON layout so Podium and Prism devices can join each other's sessions seamlessly.
 */
data class WireArtistRef(val name: String, val id: String? = null)

/** Wire representation of an album reference on a Listen Together session. */
data class WireAlbumRef(val title: String, val id: String? = null)

/**
 * Wire representation of a song on a Listen Together session. Matches Prism's `Song` JSON fields
 * byte-for-byte for cross-app compatibility on the local network.
 */
data class WireSong(
    val id: String,
    val title: String,
    val artists: List<WireArtistRef> = emptyList(),
    val album: WireAlbumRef? = null,
    val durationSec: Int = 0,
    val thumbnail: String? = null,
    val isVideo: Boolean = false,
    val explicit: Boolean = false,
    val isLive: Boolean = false,
    val isEpisode: Boolean = false,
    val podcastId: String? = null,
    val isUpload: Boolean = false,
) {
    val artistText: String get() = artists.joinToString(", ") { it.name }.ifBlank { "Unknown artist" }
}

/**
 * What devices in a Listen Together session say to each other: one JSON message per line over a
 * plain TCP connection on the local network. There is no server; the host device is the session.
 */
sealed class Msg {
    /** Guest → host, first thing: who is joining and the session's four-digit code. */
    data class Hello(val name: String, val code: String, val version: Int = VERSION) : Msg() {
        val v: Int get() = version
    }

    /** Host → guest: you're in. */
    data class Welcome(val hostName: String, val state: State? = null) : Msg()

    /** Host → guest: not let in (wrong code, or a protocol version mismatch). */
    data class Denied(val reason: String) : Msg()

    /**
     * Host → guests: what's playing. [songs] starts with the current song, then what plays next,
     * in order. [positionMs] was the playhead at [sentAt] (the host's clock).
     */
    data class State(
        val songs: List<WireSong>,
        val positionMs: Long,
        val playing: Boolean,
        val buffering: Boolean = false,
        val sentAt: Long,
        val title: String = "",
        val guests: List<String> = emptyList(),
        val canControl: Boolean = false,
    ) : Msg() {
        val song: WireSong? get() = songs.firstOrNull()
        val queue: List<WireSong> get() = if (songs.size > 1) songs.drop(1) else emptyList()
        val atMs: Long get() = sentAt
        val peers: List<String> get() = guests
        val guestsControl: Boolean get() = canControl
    }

    /** Guest → host: what time is it for you? ([t0] is the guest's clock.) */
    data class Ping(val t0: Long) : Msg()

    /** Host → guest: the answer, in the host's clock. */
    data class Pong(val t0: Long, val hostTime: Long) : Msg() {
        val tHost: Long get() = hostTime
    }

    /** Guest → host: put this song in the queue ([next] = right after the current one). */
    data class Add(val song: WireSong, val next: Boolean) : Msg()

    /** Host → everyone: someone added a song. */
    data class Added(val song: WireSong, val by: String) : Msg()

    /** Guest → host, when the host lets guests control playback: "play", "pause", "next", "previous" or "seek". */
    data class Control(val action: String, val positionMs: Long = 0) : Msg()

    /** Either way: leaving (or, from the host, the session is over). */
    data class Bye(val reason: String = "") : Msg()

    companion object {
        const val VERSION = 1
    }
}

private val VIDEO_ID_REGEX = Regex("[A-Za-z0-9_-]{11}")
private val ALLOWED_IMAGE_HOSTS = listOf(".googleusercontent.com", ".ggpht.com", ".ytimg.com")

/**
 * A song as another phone described it, validated and sanitized before entering Podium: a real
 * 11-character YouTube video id, artwork only from YouTube's image hosts over HTTPS, and bounded text.
 */
fun WireSong.fromPeer(): WireSong? {
    if (!VIDEO_ID_REGEX.matches(id)) return null
    val safeThumb = thumbnail?.takeIf { t ->
        val u = runCatching { URI(t) }.getOrNull()
        u?.scheme == "https" && u.host?.lowercase()?.let { h -> ALLOWED_IMAGE_HOSTS.any { h.endsWith(it) } } == true
    }
    return copy(
        title = title.take(300),
        artists = artists.take(10).map { it.copy(name = it.name.take(200), id = it.id?.take(64)) },
        album = album?.let { it.copy(title = it.title.take(300), id = it.id?.take(64)) },
        thumbnail = safeThumb,
    )
}

/** Converts a validated [WireSong] into a source-qualified Podium [Track] on [source]. */
fun WireSong.toPodiumTrack(source: SourceId = YouTubeMusicSource.SOURCE_ID): Track {
    val credits = if (artists.isNotEmpty()) {
        artists.map { a ->
            ArtistCredit(
                name = a.name,
                id = a.id?.takeIf { it.isNotBlank() }?.let { ArtistId.of(source, it.removePrefix("MPLA")) },
            )
        }
    } else {
        listOf(ArtistCredit("Unknown artist"))
    }
    val albumRef = album?.let { alb ->
        AlbumRef(
            title = alb.title,
            id = alb.id?.takeIf { it.startsWith("MPRE") }?.let { AlbumId.of(source, it) },
        )
    }
    val kind = when {
        isLive -> MediaKind.LIVE
        isEpisode -> MediaKind.EPISODE
        isVideo -> MediaKind.MUSIC_VIDEO
        else -> MediaKind.SONG
    }
    return Track(
        id = TrackId.of(source, id),
        source = SourceRef(
            sourceId = source,
            providerKey = id,
            providerUri = "https://music.youtube.com/watch?v=$id",
        ),
        title = title,
        artists = credits,
        artistDisplay = credits.joinToString(", ") { it.name },
        album = albumRef,
        durationMs = durationSec.takeIf { it > 0 }?.let { it * 1000L },
        artwork = thumbnail?.let { ArtworkRef(source, it) },
        explicitness = if (explicit) Explicitness.EXPLICIT else Explicitness.UNKNOWN,
        routes = setOf(PlaybackRoute.DIRECT),
        kind = kind,
    )
}

/**
 * Converts a Podium [Track] to a [WireSong] when it has a valid 11-character online video id
 * (either directly or via [overrideVideoId] from an EXACT equivalence match).
 */
fun Track.toWireSong(overrideVideoId: String? = null): WireSong? {
    val videoId = overrideVideoId ?: source.providerKey.takeIf { source.sourceId == YouTubeMusicSource.SOURCE_ID }
    if (videoId == null || !VIDEO_ID_REGEX.matches(videoId)) return null
    val thumb = artwork?.key?.takeIf { it.startsWith("https://") }
    return WireSong(
        id = videoId,
        title = title,
        artists = if (artists.isNotEmpty()) {
            artists.map { WireArtistRef(it.name, it.id?.value?.substringAfter(TrackId.SEPARATOR)) }
        } else {
            listOf(WireArtistRef(artistDisplay))
        },
        album = album?.let { WireAlbumRef(it.title, it.id?.value?.substringAfter(TrackId.SEPARATOR)) },
        durationSec = ((durationMs ?: 0L) / 1000L).toInt().coerceAtLeast(0),
        thumbnail = thumb,
        isVideo = kind == MediaKind.MUSIC_VIDEO || kind == MediaKind.VIDEO,
        explicit = explicitness == Explicitness.EXPLICIT,
        isLive = kind == MediaKind.LIVE,
        isEpisode = kind == MediaKind.EPISODE,
    )
}

internal object Wire {
    private fun encodeSong(s: WireSong): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("title", s.title)
        put(
            "artists",
            JSONArray().apply {
                for (a in s.artists) {
                    put(
                        JSONObject().apply {
                            put("name", a.name)
                            if (a.id != null) put("id", a.id)
                        },
                    )
                }
            },
        )
        if (s.album != null) {
            put(
                "album",
                JSONObject().apply {
                    put("title", s.album.title)
                    if (s.album.id != null) put("id", s.album.id)
                },
            )
        }
        put("durationSec", s.durationSec)
        if (s.thumbnail != null) put("thumbnail", s.thumbnail)
        put("isVideo", s.isVideo)
        put("explicit", s.explicit)
        put("isLive", s.isLive)
        put("isEpisode", s.isEpisode)
        if (s.podcastId != null) put("podcastId", s.podcastId)
        put("isUpload", s.isUpload)
    }

    private fun decodeSong(o: JSONObject?): WireSong? {
        if (o == null) return null
        val id = o.optString("id")
        if (id.isBlank()) return null
        val artistsArr = o.optJSONArray("artists")
        val artists = buildList {
            if (artistsArr != null) {
                for (i in 0 until artistsArr.length()) {
                    val a = artistsArr.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    if (name.isNotBlank()) add(WireArtistRef(name, a.optString("id").takeIf { it.isNotBlank() }))
                }
            }
        }
        val albObj = o.optJSONObject("album")
        val album = albObj?.let {
            val t = it.optString("title")
            if (t.isNotBlank()) WireAlbumRef(t, it.optString("id").takeIf { idStr -> idStr.isNotBlank() }) else null
        }
        return WireSong(
            id = id,
            title = o.optString("title", "Unknown"),
            artists = artists,
            album = album,
            durationSec = o.optInt("durationSec", 0),
            thumbnail = o.optString("thumbnail").takeIf { it.isNotBlank() },
            isVideo = o.optBoolean("isVideo", false),
            explicit = o.optBoolean("explicit", false),
            isLive = o.optBoolean("isLive", false),
            isEpisode = o.optBoolean("isEpisode", false),
            podcastId = o.optString("podcastId").takeIf { it.isNotBlank() },
            isUpload = o.optBoolean("isUpload", false),
        )
    }

    private fun encodeStateObj(st: Msg.State): JSONObject = JSONObject().apply {
        put("t", "state")
        put("songs", JSONArray().apply { st.songs.forEach { put(encodeSong(it)) } })
        put("positionMs", st.positionMs)
        put("playing", st.playing)
        put("buffering", st.buffering)
        put("sentAt", st.sentAt)
        put("title", st.title)
        put("guests", JSONArray().apply { st.guests.forEach { put(it) } })
        put("canControl", st.canControl)
    }

    private fun decodeStateObj(o: JSONObject): Msg.State {
        val songsArr = o.optJSONArray("songs")
        val songs = buildList {
            if (songsArr != null) {
                for (i in 0 until songsArr.length()) {
                    decodeSong(songsArr.optJSONObject(i))?.let(::add)
                }
            } else {
                decodeSong(o.optJSONObject("song"))?.let(::add)
                val qArr = o.optJSONArray("queue")
                if (qArr != null) {
                    for (i in 0 until qArr.length()) {
                        decodeSong(qArr.optJSONObject(i))?.let(::add)
                    }
                }
            }
        }
        val guestsArr = o.optJSONArray("guests") ?: o.optJSONArray("peers")
        val guests = buildList {
            if (guestsArr != null) {
                for (i in 0 until guestsArr.length()) {
                    val g = guestsArr.optString(i)
                    if (g.isNotBlank()) add(g)
                }
            }
        }
        return Msg.State(
            songs = songs,
            positionMs = o.optLong("positionMs", 0L),
            playing = o.optBoolean("playing", false),
            buffering = o.optBoolean("buffering", false),
            sentAt = o.optLong("sentAt", o.optLong("atMs", 0L)),
            title = o.optString("title", ""),
            guests = guests,
            canControl = o.optBoolean("canControl", o.optBoolean("guestsControl", false)),
        )
    }

    fun encode(m: Msg): String = when (m) {
        is Msg.Hello -> JSONObject().apply {
            put("t", "hello")
            put("name", m.name)
            put("code", m.code)
            put("version", m.version)
        }.toString()
        is Msg.Welcome -> JSONObject().apply {
            put("t", "welcome")
            put("hostName", m.hostName)
            m.state?.let { put("state", encodeStateObj(it)) }
        }.toString()
        is Msg.Denied -> JSONObject().apply {
            put("t", "denied")
            put("reason", m.reason)
        }.toString()
        is Msg.State -> encodeStateObj(m).toString()
        is Msg.Ping -> JSONObject().apply {
            put("t", "ping")
            put("t0", m.t0)
        }.toString()
        is Msg.Pong -> JSONObject().apply {
            put("t", "pong")
            put("t0", m.t0)
            put("hostTime", m.hostTime)
        }.toString()
        is Msg.Add -> JSONObject().apply {
            put("t", "add")
            put("song", encodeSong(m.song))
            put("next", m.next)
        }.toString()
        is Msg.Added -> JSONObject().apply {
            put("t", "added")
            put("song", encodeSong(m.song))
            put("by", m.by)
        }.toString()
        is Msg.Control -> JSONObject().apply {
            put("t", "control")
            put("action", m.action)
            put("positionMs", m.positionMs)
        }.toString()
        is Msg.Bye -> JSONObject().apply {
            put("t", "bye")
            put("reason", m.reason)
        }.toString()
    }

    /** Null for anything that isn't a message this version understands. */
    fun decode(line: String): Msg? = runCatching {
        val o = JSONObject(line)
        when (o.optString("t")) {
            "hello" -> Msg.Hello(
                name = o.optString("name", "Guest"),
                code = o.optString("code", ""),
                version = o.optInt("version", Msg.VERSION),
            )
            "welcome" -> Msg.Welcome(
                hostName = o.optString("hostName", "Host"),
                state = o.optJSONObject("state")?.let(::decodeStateObj),
            )
            "denied" -> Msg.Denied(o.optString("reason", ""))
            "state" -> decodeStateObj(o)
            "ping" -> Msg.Ping(o.optLong("t0", 0L))
            "pong" -> Msg.Pong(
                t0 = o.optLong("t0", 0L),
                hostTime = o.optLong("hostTime", o.optLong("tHost", 0L)),
            )
            "add" -> {
                val song = decodeSong(o.optJSONObject("song")) ?: return@runCatching null
                Msg.Add(song, o.optBoolean("next", false))
            }
            "added" -> {
                val song = decodeSong(o.optJSONObject("song")) ?: return@runCatching null
                Msg.Added(song, o.optString("by", "Guest"))
            }
            "control" -> Msg.Control(
                action = o.optString("action", ""),
                positionMs = o.optLong("positionMs", 0L),
            )
            "bye" -> Msg.Bye(o.optString("reason", ""))
            else -> null
        }
    }.getOrNull()

    /** The longest line read; anything longer is dropped. */
    const val MAX_LINE = 512 * 1024
}

/**
 * Reads lines of at most [Wire.MAX_LINE] bytes (UTF-8). Returns null at the end of the stream.
 * An over-long line is skipped whole.
 */
internal class LineReader(private val input: InputStream) {
    private val buf = ByteArrayOutputStream()

    fun readLine(): String? {
        buf.reset()
        var tooLong = false
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() > 0 && !tooLong) buf.toString(Charsets.UTF_8.name()) else null
            if (b == '\n'.code) {
                if (tooLong) {
                    tooLong = false
                    buf.reset()
                    continue
                }
                return buf.toString(Charsets.UTF_8.name())
            }
            if (!tooLong) {
                buf.write(b)
                if (buf.size() > Wire.MAX_LINE) {
                    tooLong = true
                    buf.reset()
                }
            }
        }
    }
}

/** One TCP connection between two devices: messages out with [send], in with [read]. */
internal class Link(private val socket: Socket) : Closeable {
    private val reader = LineReader(socket.getInputStream().buffered())
    private val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)

    /** False if the connection has gone. Safe to call from any thread. */
    fun send(m: Msg): Boolean = synchronized(writer) {
        runCatching {
            writer.write(Wire.encode(m))
            writer.write("\n")
            writer.flush()
        }.isSuccess
    }

    /** The next message, skipping lines it doesn't understand; null once the connection closes. Blocks. */
    fun read(): Msg? {
        while (true) {
            val line = runCatching { reader.readLine() }.getOrNull() ?: return null
            if (line.isBlank()) continue
            Wire.decode(line)?.let { return it }
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

/**
 * The guest's estimate of the host's clock, NTP-style: each ping's round trip halves into the
 * offset; the sample with the shortest trip is trusted most.
 */
internal class ClockSync {
    private var bestRtt = Long.MAX_VALUE
    private var bestAt = 0L

    /** Host clock minus guest clock, in ms. */
    @Volatile
    var offset = 0L
        private set

    @Volatile
    var samples = 0
        private set

    val ready: Boolean get() = samples > 0

    fun reset() {
        bestRtt = Long.MAX_VALUE
        bestAt = 0L
        offset = 0L
        samples = 0
    }

    fun hostNow(guestNow: Long): Long = guestNow + offset

    /** [t1] is the guest's clock when the pong arrived. */
    fun onPong(p: Msg.Pong, t1: Long) = onPong(p.t0, p.hostTime, t1)

    fun onPong(t0: Long, hostTime: Long, t1: Long) {
        val rtt = (t1 - t0).coerceAtLeast(0)
        val stale = t1 - bestAt > 60_000
        if (rtt <= bestRtt || stale) {
            bestRtt = rtt
            bestAt = t1
            offset = hostTime - (t0 + rtt / 2)
        }
        samples++
    }

    /** Where the host's playhead is now, by the guest's clock [now]. */
    fun hostPosition(s: Msg.State, now: Long): Long =
        if (s.playing && !s.buffering) s.positionMs + (now + offset - s.sentAt).coerceAtLeast(0) else s.positionMs
}
