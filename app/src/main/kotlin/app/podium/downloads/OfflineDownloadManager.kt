package app.podium.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.podium.AppGraph
import app.podium.core.lyrics.LyricsRequest
import app.podium.core.model.AlbumId
import app.podium.core.model.AlbumRef
import app.podium.core.model.ArtistCredit
import app.podium.core.model.ArtistId
import app.podium.core.model.ArtistRole
import app.podium.core.model.ArtworkRef
import app.podium.core.model.AudioQuality
import app.podium.core.model.Availability
import app.podium.core.model.MediaKind
import app.podium.core.model.PlaybackRoute
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.sources.api.ArtworkPayload
import app.podium.sources.api.PlayableMedia
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.Purpose
import app.podium.sources.api.QualityRequest
import app.podium.sources.api.resolve.OwnedCopyLocator
import app.podium.sources.api.resolve.ResolveOutcome
import app.podium.sources.api.resolve.ResolveRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
}

data class DownloadEntry(
    val track: Track,
    val status: DownloadStatus,
    val progress: Int = 0,
    val bytes: Long = 0L,
    val addedAt: Long = System.currentTimeMillis(),
    val smart: Boolean = false,
    val claimedKbps: Int? = null,
    val mimeType: String? = null,
    val error: String? = null,
)

/**
 * Manages offline song downloads (audio stream, cover artwork, lyrics, and track catalog metadata)
 * and acts as Podium's [OwnedCopyLocator] so downloaded songs play straight from disk even offline.
 */
class OfflineDownloadManager(
    private val context: Context,
    private val graph: AppGraph,
) : OwnedCopyLocator {

    private val prefs = context.getSharedPreferences("podium_downloads", Context.MODE_PRIVATE)
    private val rootDir = File(context.filesDir, "downloads").apply { mkdirs() }
    private val audioDir = File(rootDir, "audio").apply { mkdirs() }
    private val coverDir = File(rootDir, "covers").apply { mkdirs() }
    private val indexFile = File(rootDir, "index.json")

    private val _entries = MutableStateFlow<Map<TrackId, DownloadEntry>>(emptyMap())
    val entries: StateFlow<Map<TrackId, DownloadEntry>> = _entries.asStateFlow()

    /** Ordered list of all download entries (active first, then newest completed first). */
    val allEntries: StateFlow<List<DownloadEntry>> = _entries
        .map { map ->
            map.values.sortedWith(
                compareBy<DownloadEntry> {
                    when (it.status) {
                        DownloadStatus.DOWNLOADING -> 0
                        DownloadStatus.QUEUED -> 1
                        DownloadStatus.FAILED -> 2
                        DownloadStatus.COMPLETED -> 3
                    }
                }.thenByDescending { it.addedAt },
            )
        }
        .stateIn(graph.appScope, SharingStarted.Eagerly, emptyList())

    /** Completed offline tracks, newest first. */
    val completedTracks: StateFlow<List<Track>> = allEntries
        .map { list -> list.filter { it.status == DownloadStatus.COMPLETED }.map { it.track } }
        .stateIn(graph.appScope, SharingStarted.Eagerly, emptyList())

    private val _smartDownloadsEnabled = MutableStateFlow(prefs.getBoolean(KEY_SMART_DOWNLOADS, false))
    val smartDownloadsEnabled: StateFlow<Boolean> = _smartDownloadsEnabled.asStateFlow()

    private val jobs = ConcurrentHashMap<TrackId, Job>()
    private val artworkToCover = ConcurrentHashMap<String, File>()
    private val downloadSemaphore = Semaphore(2)

    init {
        val loaded = loadIndex()
        _entries.value = loaded
        val completed = loaded.values.filter { it.status == DownloadStatus.COMPLETED }.map { it.track }
        if (completed.isNotEmpty()) {
            graph.catalog.remember(completed)
        }
        loaded.values.forEach { entry ->
            val cover = coverFileFor(entry.track.id)
            val artUri = entry.track.artwork?.uri
            if (artUri != null && cover.exists() && cover.length() > 0L) {
                artworkToCover[artUri] = cover
            }
        }
        observeSmartDownloads()
    }

    fun setSmartDownloads(enabled: Boolean) {
        _smartDownloadsEnabled.value = enabled
        prefs.edit().putBoolean(KEY_SMART_DOWNLOADS, enabled).apply()
    }

    fun isDownloaded(trackId: TrackId): Boolean =
        _entries.value[trackId]?.status == DownloadStatus.COMPLETED && isValidAudioFile(audioFileFor(trackId))

    fun findTrack(trackId: TrackId): Track? {
        val entry = _entries.value[trackId] ?: return null
        if (entry.status != DownloadStatus.COMPLETED) return null
        if (!isValidAudioFile(audioFileFor(trackId))) return null
        return entry.track
    }

    fun isDownloading(trackId: TrackId): Boolean {
        val st = _entries.value[trackId]?.status
        return st == DownloadStatus.QUEUED || st == DownloadStatus.DOWNLOADING
    }

    override suspend fun find(track: Track): Pair<Track, PlayableMedia>? {
        val entry = _entries.value[track.id] ?: return null
        if (entry.status != DownloadStatus.COMPLETED) return null
        val file = audioFileFor(track.id)
        if (!isValidAudioFile(file)) return null
        val mime = detectAudioMimeType(file, entry.mimeType)
        val quality = entry.claimedKbps?.takeIf { it > 0 }?.let { AudioQuality(bitrateKbps = it) }
        return entry.track to PlayableMedia(
            uri = android.net.Uri.fromFile(file).toString(),
            mimeType = mime,
            sourceId = track.source.sourceId,
            claimedQuality = quality,
            cacheKey = "${track.id.value}|offline",
        )
    }

    fun loadArtwork(ref: ArtworkRef): ArtworkPayload? {
        val file = artworkToCover[ref.uri] ?: return null
        if (file.exists() && file.length() > 0L) {
            return ArtworkPayload.LocalFile(file.absolutePath)
        }
        return null
    }

    fun download(track: Track, smart: Boolean = false) {
        if (track.kind == MediaKind.LIVE || track.source.sourceId.value == "radio") return
        val existing = _entries.value[track.id]
        if (existing != null && (existing.status == DownloadStatus.COMPLETED || existing.status == DownloadStatus.DOWNLOADING || existing.status == DownloadStatus.QUEUED)) {
            if (existing.status == DownloadStatus.COMPLETED && isValidAudioFile(audioFileFor(track.id))) return
            if (existing.status != DownloadStatus.COMPLETED) return
        }
        graph.catalog.remember(listOf(track))
        val queued = DownloadEntry(
            track = track,
            status = DownloadStatus.QUEUED,
            progress = 0,
            bytes = 0L,
            addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            smart = smart,
        )
        updateEntry(queued, persist = true)

        jobs[track.id]?.cancel()
        jobs[track.id] = graph.appScope.launch(Dispatchers.IO) {
            downloadSemaphore.withPermit {
                performDownload(queued)
            }
        }
    }

    fun downloadAll(tracks: List<Track>, smart: Boolean = false) {
        tracks.forEach { download(it, smart = smart) }
    }

    fun retryFailed() {
        val failed = _entries.value.values.filter { it.status == DownloadStatus.FAILED }
        failed.forEach { entry ->
            _entries.value = _entries.value - entry.track.id
            download(entry.track, smart = entry.smart)
        }
    }

    fun remove(trackId: TrackId) {
        jobs.remove(trackId)?.cancel()
        val entry = _entries.value[trackId]
        entry?.track?.artwork?.uri?.let { artworkToCover.remove(it) }
        audioFileFor(trackId).delete()
        tempFileFor(trackId).delete()
        coverFileFor(trackId).delete()
        _entries.value = _entries.value - trackId
        saveIndex(_entries.value)
    }

    fun removeAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        artworkToCover.clear()
        audioDir.listFiles()?.forEach { it.delete() }
        coverDir.listFiles()?.forEach { it.delete() }
        _entries.value = emptyMap()
        saveIndex(emptyMap())
    }

    private suspend fun performDownload(initial: DownloadEntry) {
        val track = initial.track
        val id = track.id
        try {
            updateEntry(initial.copy(status = DownloadStatus.DOWNLOADING, progress = 2, error = null), persist = false)

            // 1. Resolve highest-quality stream URL and headers through Podium's StreamResolver
            val resolved = graph.resolver.resolve(
                ResolveRequest(
                    track = track,
                    purpose = Purpose.PLAYBACK,
                    quality = QualityRequest.Maximum,
                ),
            )
            val target = (resolved as? ResolveOutcome.Resolved)?.selection?.target as? PlaybackTarget.DirectStream
                ?: throw IllegalStateException("Could not resolve playable audio stream")
            val media = target.media

            // 2. Download audio stream to temporary file, then atomically rename
            val tmpFile = tempFileFor(id)
            val finalFile = audioFileFor(id)
            streamToFile(
                url = media.uri,
                headers = media.headers,
                dest = tmpFile,
                onProgress = { pct, bytesRead ->
                    updateEntry(
                        initial.copy(
                            status = DownloadStatus.DOWNLOADING,
                            progress = pct.coerceIn(5, 95),
                            bytes = bytesRead,
                        ),
                        persist = false,
                    )
                },
            )
            if (!isValidAudioFile(tmpFile)) {
                tmpFile.delete()
                throw IllegalStateException("Downloaded audio file is empty or corrupt")
            }
            if (finalFile.exists()) finalFile.delete()
            if (!tmpFile.renameTo(finalFile)) {
                tmpFile.copyTo(finalFile, overwrite = true)
                tmpFile.delete()
            }

            // 3. Save cover artwork for offline display
            val artUri = track.artwork?.uri
            if (artUri != null) {
                runCatching {
                    val coverFile = coverFileFor(id)
                    when (val payload = graph.artwork.load(artUri, 600)) {
                        is ArtworkPayload.Bytes -> {
                            if (payload.bytes.isNotEmpty()) {
                                coverFile.writeBytes(payload.bytes)
                                artworkToCover[artUri] = coverFile
                            }
                        }
                        is ArtworkPayload.LocalFile -> {
                            val src = File(payload.path)
                            if (src.exists() && src.absolutePath != coverFile.absolutePath) {
                                src.copyTo(coverFile, overwrite = true)
                            }
                            if (coverFile.exists()) artworkToCover[artUri] = coverFile
                        }
                        null -> Unit
                    }
                }
            }

            // 4. Prefetch and cache lyrics for offline display
            runCatching {
                graph.lyrics.lyrics(
                    LyricsRequest(
                        key = track.id.value,
                        title = track.title,
                        artist = track.artistDisplay,
                        album = track.album?.title,
                        durationMs = track.durationMs,
                    ),
                )
            }

            // 5. Mark completed & persist
            val detectedMime = detectAudioMimeType(finalFile, media.mimeType)
            val completed = initial.copy(
                status = DownloadStatus.COMPLETED,
                progress = 100,
                bytes = finalFile.length(),
                claimedKbps = media.claimedQuality?.bitrateKbps,
                mimeType = detectedMime,
                error = null,
            )
            graph.catalog.remember(listOf(track))
            updateEntry(completed, persist = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tempFileFor(id).delete()
            updateEntry(
                initial.copy(
                    status = DownloadStatus.FAILED,
                    progress = 0,
                    error = e.message ?: "Download failed",
                ),
                persist = true,
            )
        } finally {
            jobs.remove(id)
        }
    }

    private fun streamToFile(
        url: String,
        headers: Map<String, String>,
        dest: File,
        onProgress: (percent: Int, bytesRead: Long) -> Unit,
    ) {
        val expectedFromUrl = url.substringAfter('?', "")
            .split('&')
            .firstOrNull { it.startsWith("clen=") }
            ?.substringAfter('=')
            ?.toLongOrNull()
            ?.takeIf { it > 0L }

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", headers["User-Agent"] ?: "Mozilla/5.0 (Android)")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if ("Range" !in headers) {
                    setRequestProperty("Range", "bytes=0-")
                }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            val totalBytes = conn.contentLengthLong.takeIf { it > 0L } ?: expectedFromUrl ?: -1L
            var downloaded = 0L
            var lastReportedPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val pct = if (totalBytes > 0L) {
                            ((downloaded * 100L) / totalBytes).toInt().coerceIn(5, 95)
                        } else {
                            (10 + (downloaded / (128 * 1024)).toInt()).coerceIn(10, 90)
                        }
                        if (pct != lastReportedPct) {
                            lastReportedPct = pct
                            onProgress(pct, downloaded)
                        }
                    }
                    output.flush()
                }
            }
            // If YouTube CDN closed the first open-ended connection early before reaching totalBytes,
            // resume downloading the remaining range(s).
            var retries = 0
            while (totalBytes > 0L && downloaded in 1 until totalBytes && retries < 8) {
                conn?.disconnect()
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", headers["User-Agent"] ?: "Mozilla/5.0 (Android)")
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                    setRequestProperty("Range", "bytes=$downloaded-")
                }
                if (conn.responseCode != 206) break
                var chunkRead = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(dest, /* append = */ true).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            chunkRead += read
                            val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(5, 95)
                            if (pct != lastReportedPct) {
                                lastReportedPct = pct
                                onProgress(pct, downloaded)
                            }
                        }
                        output.flush()
                    }
                }
                if (chunkRead <= 0L) retries++ else retries = 0
            }
        } finally {
            conn?.disconnect()
        }
    }

    private fun isValidAudioFile(file: File): Boolean {
        if (!file.exists() || file.length() < 8 * 1024L) return false
        return runCatching {
            val head = ByteArray(16)
            val read = file.inputStream().use { it.read(head) }
            if (read < 8) return false
            val textPrefix = String(head, 0, read, Charsets.ISO_8859_1).trimStart()
            if (textPrefix.startsWith("#EXTM3U", ignoreCase = true) ||
                textPrefix.startsWith("<!DOCTYPE", ignoreCase = true) ||
                textPrefix.startsWith("<html", ignoreCase = true) ||
                textPrefix.startsWith("<?xml", ignoreCase = true) ||
                textPrefix.startsWith("{")
            ) {
                return false
            }
            true
        }.getOrDefault(false)
    }

    private fun detectAudioMimeType(file: File, fallback: String?): String? {
        runCatching {
            val head = ByteArray(16)
            val read = file.inputStream().use { it.read(head) }
            if (read >= 12) {
                // EBML / WebM / Matroska: 0x1A 0x45 0xDF 0xA3
                if (head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()) {
                    return "audio/webm"
                }
                // ISO BMFF / MP4 / M4A: "ftyp" at offset 4..7
                if (head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() && head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()) {
                    return "audio/mp4"
                }
                // Ogg: "OggS"
                if (head[0] == 'O'.code.toByte() && head[1] == 'g'.code.toByte() && head[2] == 'g'.code.toByte() && head[3] == 'S'.code.toByte()) {
                    return "audio/ogg"
                }
                // FLAC: "fLaC"
                if (head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() && head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte()) {
                    return "audio/flac"
                }
                // WAV: "RIFF"...."WAVE"
                if (head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() &&
                    head[8] == 'W'.code.toByte() && head[9] == 'A'.code.toByte() && head[10] == 'V'.code.toByte() && head[11] == 'E'.code.toByte()
                ) {
                    return "audio/wav"
                }
                // MP3 ID3 tag ("ID3") or MPEG frame sync (0xFF 0xE0..0xFF)
                if ((head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) ||
                    (head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0)
                ) {
                    return "audio/mpeg"
                }
            }
        }
        return fallback?.takeIf { it.startsWith("audio/") }
    }

    private fun observeSmartDownloads() {
        graph.appScope.launch(Dispatchers.IO) {
            combine(
                _smartDownloadsEnabled,
                graph.online.likedTracks,
                graph.online.recentlyPlayed,
            ) { enabled, liked, recent ->
                if (!enabled) emptyList() else (liked.take(15) + recent.take(15)).distinctBy { it.id }.take(25)
            }.collect { candidates ->
                if (candidates.isEmpty() || !isUnmeteredNetwork()) return@collect
                delay(1_500L)
                if (!_smartDownloadsEnabled.value || !isUnmeteredNetwork()) return@collect
                for (track in candidates) {
                    if (!_smartDownloadsEnabled.value) break
                    if (!isDownloaded(track.id) && !isDownloading(track.id)) {
                        download(track, smart = true)
                    }
                }
            }
        }
    }

    private fun isUnmeteredNetwork(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    private fun safeFileName(id: TrackId): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(id.value.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it) }
        return digest
    }

    private fun audioFileFor(id: TrackId): File = File(audioDir, "${safeFileName(id)}.audio")
    private fun tempFileFor(id: TrackId): File = File(audioDir, "${safeFileName(id)}.tmp")
    private fun coverFileFor(id: TrackId): File = File(coverDir, "${safeFileName(id)}.img")

    @Synchronized
    private fun updateEntry(entry: DownloadEntry, persist: Boolean) {
        val updated = _entries.value + (entry.track.id to entry)
        _entries.value = updated
        if (persist) {
            saveIndex(updated)
        }
    }

    @Synchronized
    private fun saveIndex(map: Map<TrackId, DownloadEntry>) {
        runCatching {
            val arr = JSONArray()
            for (entry in map.values) {
                arr.put(encodeEntry(entry))
            }
            indexFile.writeText(arr.toString())
        }
    }

    private fun loadIndex(): Map<TrackId, DownloadEntry> = runCatching {
        if (!indexFile.exists()) return emptyMap()
        val arr = JSONArray(indexFile.readText())
        val map = LinkedHashMap<TrackId, DownloadEntry>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val entry = decodeEntry(obj) ?: continue
            val file = audioFileFor(entry.track.id)
            val resolvedStatus = when (entry.status) {
                DownloadStatus.COMPLETED -> if (isValidAudioFile(file)) DownloadStatus.COMPLETED else DownloadStatus.FAILED
                DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> DownloadStatus.FAILED
                DownloadStatus.FAILED -> DownloadStatus.FAILED
            }
            val resolvedMime = if (resolvedStatus == DownloadStatus.COMPLETED) {
                detectAudioMimeType(file, entry.mimeType)
            } else {
                entry.mimeType
            }
            map[entry.track.id] = entry.copy(
                status = resolvedStatus,
                progress = if (resolvedStatus == DownloadStatus.COMPLETED) 100 else 0,
                mimeType = resolvedMime,
            )
        }
        map
    }.getOrDefault(emptyMap())

    private fun encodeEntry(entry: DownloadEntry): JSONObject {
        val t = entry.track
        return JSONObject().apply {
            put("trackId", t.id.value)
            put("sourceId", t.source.sourceId.value)
            put("providerKey", t.source.providerKey)
            t.source.providerUri?.let { put("providerUri", it) }
            t.source.providerData?.let { put("providerData", it) }
            put("title", t.title)
            put("artistDisplay", t.artistDisplay)
            put(
                "artists",
                JSONArray().apply {
                    t.artists.forEach { a ->
                        put(
                            JSONObject().apply {
                                put("name", a.name)
                                put("role", a.role.name)
                                a.id?.value?.let { put("id", it) }
                            },
                        )
                    }
                },
            )
            t.album?.let { alb ->
                put("albumTitle", alb.title)
                alb.id?.value?.let { put("albumId", it) }
                alb.albumArtist?.let { put("albumArtist", it) }
            }
            t.durationMs?.let { put("durationMs", it) }
            t.artwork?.let { art ->
                put("artSourceId", art.sourceId.value)
                put("artKey", art.key)
            }
            put("kind", t.kind.name)
            put("status", entry.status.name)
            put("bytes", entry.bytes)
            put("addedAt", entry.addedAt)
            put("smart", entry.smart)
            entry.claimedKbps?.let { put("claimedKbps", it) }
            entry.mimeType?.let { put("mimeType", it) }
            entry.error?.let { put("error", it) }
        }
    }

    private fun decodeEntry(obj: JSONObject): DownloadEntry? = runCatching {
        val trackId = TrackId(obj.getString("trackId"))
        val sourceId = SourceId(obj.getString("sourceId"))
        val providerKey = obj.getString("providerKey")
        val providerUri = obj.optString("providerUri").takeIf { it.isNotBlank() }
        val providerData = obj.optString("providerData").takeIf { it.isNotBlank() }
        val title = obj.getString("title")
        val artistDisplay = obj.optString("artistDisplay", "")
        val artistsArr = obj.optJSONArray("artists")
        val artists = buildList {
            if (artistsArr != null) {
                for (i in 0 until artistsArr.length()) {
                    val a = artistsArr.optJSONObject(i) ?: continue
                    val role = runCatching { ArtistRole.valueOf(a.optString("role", "PRIMARY")) }.getOrDefault(ArtistRole.PRIMARY)
                    val aId = a.optString("id").takeIf { it.isNotBlank() }?.let(::ArtistId)
                    add(ArtistCredit(name = a.optString("name", ""), role = role, id = aId))
                }
            }
            if (isEmpty() && artistDisplay.isNotBlank()) {
                add(ArtistCredit(artistDisplay))
            }
        }
        val albumTitle = obj.optString("albumTitle").takeIf { it.isNotBlank() }
        val album = albumTitle?.let {
            AlbumRef(
                title = it,
                id = obj.optString("albumId").takeIf { s -> s.isNotBlank() }?.let(::AlbumId),
                albumArtist = obj.optString("albumArtist").takeIf { s -> s.isNotBlank() },
            )
        }
        val durationMs = if (obj.has("durationMs")) obj.getLong("durationMs") else null
        val artwork = if (obj.has("artSourceId") && obj.has("artKey")) {
            ArtworkRef(SourceId(obj.getString("artSourceId")), obj.getString("artKey"))
        } else {
            null
        }
        val kind = runCatching { MediaKind.valueOf(obj.optString("kind", "SONG")) }.getOrDefault(MediaKind.SONG)
        val status = runCatching { DownloadStatus.valueOf(obj.optString("status", "COMPLETED")) }.getOrDefault(DownloadStatus.COMPLETED)
        val track = Track(
            id = trackId,
            source = SourceRef(sourceId, providerKey, providerUri, providerData),
            title = title,
            artists = artists,
            artistDisplay = artistDisplay.ifBlank { artists.joinToString(", ") { it.name } },
            album = album,
            durationMs = durationMs,
            artwork = artwork,
            availability = Availability.Playable,
            routes = setOf(PlaybackRoute.DIRECT),
            kind = kind,
        )
        DownloadEntry(
            track = track,
            status = status,
            progress = if (status == DownloadStatus.COMPLETED) 100 else 0,
            bytes = obj.optLong("bytes", 0L),
            addedAt = obj.optLong("addedAt", 0L),
            smart = obj.optBoolean("smart", false),
            claimedKbps = if (obj.has("claimedKbps")) obj.optInt("claimedKbps") else null,
            mimeType = obj.optString("mimeType").takeIf { it.isNotBlank() },
            error = obj.optString("error").takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    companion object {
        private const val KEY_SMART_DOWNLOADS = "smart_downloads_enabled"
    }
}
