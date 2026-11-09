package app.podium.radio

import android.content.Context
import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.AlbumId
import app.podium.core.model.ArtistId
import app.podium.core.model.ArtworkRef
import app.podium.core.model.AudioQuality
import app.podium.core.model.PlaybackRoute
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.sources.api.AlbumDetail
import app.podium.sources.api.ArtistDetail
import app.podium.sources.api.ArtworkFacet
import app.podium.sources.api.ArtworkPayload
import app.podium.sources.api.Basis
import app.podium.sources.api.Capability
import app.podium.sources.api.CatalogFacet
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.MissReason
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.MusicSource
import app.podium.sources.api.PlayableMedia
import app.podium.sources.api.PlaybackFacet
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.Purpose
import app.podium.sources.api.QualityRequest
import app.podium.sources.api.SearchQuery
import app.podium.sources.api.SearchResults
import app.podium.sources.api.SourceCapabilities
import app.podium.sources.api.SourceDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Curated Tunisian stations and Radio Browser directory client.
 */
class RadioApi {
    private val hosts = listOf(
        "https://de1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info",
    )

    private suspend fun fetch(path: String): String? = withContext(Dispatchers.IO) {
        for (host in hosts) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("$host$path").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7_000
                    readTimeout = 8_000
                    setRequestProperty("User-Agent", "Podium/1.0")
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode in 200..299) {
                    return@withContext conn.inputStream.bufferedReader().use { it.readText() }
                }
            } catch (_: Exception) {
                // Try next Radio Browser mirror
            } finally {
                conn?.disconnect()
            }
        }
        null
    }

    private fun parseStations(raw: String): List<RadioStation> = runCatching {
        val arr = JSONArray(raw)
        buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("stationuuid").trim()
                val name = obj.optString("name").trim()
                val streamUrl = obj.optString("url_resolved").trim().ifBlank { obj.optString("url").trim() }
                if (id.isBlank() || name.isBlank() || streamUrl.isBlank()) continue
                add(
                    RadioStation(
                        id = id,
                        name = name,
                        streamUrl = streamUrl,
                        favicon = obj.optString("favicon").trim().takeIf { it.isNotBlank() },
                        country = obj.optString("country").trim().takeIf { it.isNotBlank() },
                        countryCode = obj.optString("countrycode").trim().takeIf { it.isNotBlank() },
                        state = obj.optString("state").trim().takeIf { it.isNotBlank() },
                        language = obj.optString("language").trim().takeIf { it.isNotBlank() },
                        tags = obj.optString("tags").trim().takeIf { it.isNotBlank() },
                        homepage = obj.optString("homepage").trim().takeIf { it.isNotBlank() },
                        codec = obj.optString("codec").trim().takeIf { it.isNotBlank() },
                        bitrate = obj.optInt("bitrate", 0),
                        votes = obj.optInt("votes", 0),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    suspend fun getTunisianStations(): List<RadioStation> = withContext(Dispatchers.IO) {
        val result = mutableListOf<RadioStation>()
        result.addAll(CURATED_TUNISIA)
        val remoteJson = fetch("/json/stations/bycountrycodeexact/tn?order=votes&reverse=true")
            ?: fetch("/json/stations/bycountry/tunisia?order=votes&reverse=true")
        if (!remoteJson.isNullOrBlank()) {
            for (item in parseStations(remoteJson)) {
                if (result.none { it.id == item.id || it.name.equals(item.name, ignoreCase = true) }) {
                    result.add(item)
                }
            }
        }
        result
    }

    suspend fun getTopStations(limit: Int = 40): List<RadioStation> = withContext(Dispatchers.IO) {
        val raw = fetch("/json/stations/topvote/$limit") ?: return@withContext emptyList()
        parseStations(raw)
    }

    suspend fun getStationsByTag(tag: String, limit: Int = 40): List<RadioStation> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(tag.trim().lowercase(), StandardCharsets.UTF_8.name())
        val raw = fetch("/json/stations/bytag/$encoded?order=votes&reverse=true&limit=$limit")
            ?: return@withContext emptyList()
        parseStations(raw)
    }

    suspend fun searchStations(query: String, limit: Int = 40): List<RadioStation> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext emptyList()
        val curatedMatches = CURATED_TUNISIA.filter {
            it.name.contains(q, ignoreCase = true) ||
                (it.tags?.contains(q, ignoreCase = true) == true) ||
                (it.country?.contains(q, ignoreCase = true) == true)
        }
        val encoded = URLEncoder.encode(q, StandardCharsets.UTF_8.name())
        val raw = fetch("/json/stations/search?name=$encoded&order=votes&reverse=true&limit=$limit")
        val remote = if (!raw.isNullOrBlank()) parseStations(raw) else emptyList()
        (curatedMatches + remote).distinctBy { it.id }
    }

    companion object {
        val CURATED_TUNISIA = listOf(
            RadioStation(
                id = "tn_mosaique",
                name = "Mosaique FM",
                streamUrl = "https://radio.mosaiquefm.net/mosalive",
                favicon = "https://www.mosaiquefm.net/assets/images/favicon.ico",
                country = "Tunisia",
                countryCode = "TN",
                tags = "news, pop, music, talk, generalist",
                homepage = "https://www.mosaiquefm.net",
                codec = "MP3",
                bitrate = 128,
            ),
            RadioStation(
                id = "tn_jawhara",
                name = "Jawhara FM",
                streamUrl = "https://streaming2.toutech.net/jawharafm",
                favicon = "https://www.jawharafm.net/favicon.ico",
                country = "Tunisia",
                countryCode = "TN",
                tags = "news, music, talk, sahel",
                homepage = "https://www.jawharafm.net",
                codec = "MP3",
                bitrate = 128,
            ),
            RadioStation(
                id = "tn_diwan",
                name = "Diwan FM",
                streamUrl = "https://streaming.diwanfm.net/stream",
                favicon = "https://diwanfm.net/favicon.ico",
                country = "Tunisia",
                countryCode = "TN",
                tags = "news, talk, debate, sfax, sports",
                homepage = "https://diwanfm.net",
                codec = "MP3",
                bitrate = 128,
            ),
            RadioStation(
                id = "tn_ifm",
                name = "IFM 100.6",
                streamUrl = "https://stream.zeno.fm/0rehsamc9xxtv",
                favicon = "https://www.ifm.tn/favicon.ico",
                country = "Tunisia",
                countryCode = "TN",
                tags = "music, humor, talk, youth",
                homepage = "https://www.ifm.tn",
                codec = "MP3",
                bitrate = 128,
            ),
            RadioStation(
                id = "tn_express",
                name = "Express FM",
                streamUrl = "https://expressfm.ice.infomaniak.ch/expressfm-64.mp3",
                favicon = "https://radioexpressfm.com/favicon.ico",
                country = "Tunisia",
                countryCode = "TN",
                tags = "economy, business, news, jazz",
                homepage = "https://radioexpressfm.com",
                codec = "MP3",
                bitrate = 64,
            ),
            RadioStation(
                id = "tn_nationale",
                name = "Radio Tunis Nationale",
                streamUrl = "http://rtstream.tanitweb.com/nationale",
                favicon = "http://www.radiotunisienne.tn/favicon.ico",
                country = "Tunisia",
                countryCode = "TN",
                tags = "public, news, culture, tunisia",
                homepage = "http://www.radiotunisienne.tn",
                codec = "MP3",
                bitrate = 128,
            ),
            RadioStation(
                id = "tn_zitouna",
                name = "Zitouna FM",
                streamUrl = "http://stream6.tanitweb.com/zitounafm",
                favicon = "http://www.zitounafm.net/favicon.ico",
                country = "Tunisia",
                countryCode = "TN",
                tags = "religious, quran, islamic, culture",
                homepage = "http://www.zitounafm.net",
                codec = "MP3",
                bitrate = 128,
            ),
            RadioStation(
                id = "tn_knooz",
                name = "KnOOz FM",
                streamUrl = "http://streaming.knoozfm.net:8000/knoozfm",
                favicon = "http://knoozfm.net/favicon.ico",
                country = "Tunisia",
                countryCode = "TN",
                tags = "music, hits, entertainment, sousse",
                homepage = "http://knoozfm.net",
                codec = "MP3",
                bitrate = 128,
            ),
            RadioStation(
                id = "tn_radiomed",
                name = "Radio Med",
                streamUrl = "http://radiomed.ice.infomaniak.ch/radiomed-64.mp3",
                favicon = "https://radiomedtunisie.com/favicon.ico",
                country = "Tunisia",
                countryCode = "TN",
                tags = "nabeul, cap bon, news, music",
                homepage = "https://radiomedtunisie.com",
                codec = "MP3",
                bitrate = 64,
            ),
            RadioStation(
                id = "tn_sabra",
                name = "Sabra FM",
                streamUrl = "http://stream6.tanitweb.com/sabrafm",
                favicon = "https://www.radiosabrafm.net/favicon.ico",
                country = "Tunisia",
                countryCode = "TN",
                tags = "kairouan, news, central tunisia",
                homepage = "https://www.radiosabrafm.net",
                codec = "MP3",
                bitrate = 128,
            ),
            RadioStation(
                id = "tn_oxygene",
                name = "Oxygène FM",
                streamUrl = "http://stream6.tanitweb.com/oxygene",
                favicon = "https://oxygene.fm/favicon.ico",
                country = "Tunisia",
                countryCode = "TN",
                tags = "bizerte, north, music, talk",
                homepage = "https://oxygene.fm",
                codec = "MP3",
                bitrate = 128,
            ),
            RadioStation(
                id = "tn_monastir",
                name = "Radio Monastir",
                streamUrl = "http://rtstream.tanitweb.com/monastir",
                country = "Tunisia",
                countryCode = "TN",
                tags = "public, regional, monastir",
                homepage = "http://www.radiomonastir.tn",
                codec = "MP3",
                bitrate = 128,
            ),
            RadioStation(
                id = "tn_sfax",
                name = "Radio Sfax",
                streamUrl = "http://rtstream.tanitweb.com/sfax",
                country = "Tunisia",
                countryCode = "TN",
                tags = "public, regional, sfax",
                homepage = "http://www.radiosfax.tn",
                codec = "MP3",
                bitrate = 128,
            ),
            RadioStation(
                id = "tn_gafsa",
                name = "Radio Gafsa",
                streamUrl = "http://rtstream.tanitweb.com/gafsa",
                country = "Tunisia",
                countryCode = "TN",
                tags = "public, regional, south-west",
                homepage = "http://www.radiogafsa.tn",
                codec = "MP3",
                bitrate = 128,
            ),
            RadioStation(
                id = "tn_tataouine",
                name = "Radio Tataouine",
                streamUrl = "http://rtstream.tanitweb.com/tataouine",
                country = "Tunisia",
                countryCode = "TN",
                tags = "public, regional, south",
                homepage = "http://www.radiotataouine.tn",
                codec = "MP3",
                bitrate = 128,
            ),
            RadioStation(
                id = "tn_kef",
                name = "Radio Le Kef",
                streamUrl = "http://rtstream.tanitweb.com/kef",
                country = "Tunisia",
                countryCode = "TN",
                tags = "public, regional, north-west",
                homepage = "http://www.radiokef.tn",
                codec = "MP3",
                bitrate = 128,
            ),
            RadioStation(
                id = "tn_jeunes",
                name = "Radio Jeunes",
                streamUrl = "http://rtstream.tanitweb.com/jeunes",
                country = "Tunisia",
                countryCode = "TN",
                tags = "public, youth, pop, entertainment",
                homepage = "http://www.radiojeunes.tn",
                codec = "MP3",
                bitrate = 128,
            ),
            RadioStation(
                id = "tn_culturelle",
                name = "Radio Culturelle",
                streamUrl = "http://rtstream.tanitweb.com/culturelle",
                country = "Tunisia",
                countryCode = "TN",
                tags = "public, culture, arts, literature",
                homepage = "http://www.radioculturelle.tn",
                codec = "MP3",
                bitrate = 128,
            ),
            RadioStation(
                id = "tn_salma_quran",
                name = "إذاعة القرآن الكريم (تلاوات خاشعة)",
                streamUrl = "https://qurango.net/radio/salma",
                country = "Tunisia",
                countryCode = "TN",
                tags = "quran, recitation, classical, islam",
                homepage = "https://qurango.net",
                codec = "MP3",
                bitrate = 128,
            ),
        )
    }
}

/**
 * Local persistence + runtime registry for Online Radio stations (favorites, recents, custom stations).
 */
class RadioRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("podium_radio", Context.MODE_PRIVATE)
    private val api = RadioApi()
    private val known = ConcurrentHashMap<String, RadioStation>()
    private val overrides = ConcurrentHashMap<String, RadioStation>()

    private val _favorites = MutableStateFlow<List<RadioStation>>(emptyList())
    val favorites: StateFlow<List<RadioStation>> = _favorites.asStateFlow()

    private val _recents = MutableStateFlow<List<RadioStation>>(emptyList())
    val recents: StateFlow<List<RadioStation>> = _recents.asStateFlow()

    private val _custom = MutableStateFlow<List<RadioStation>>(emptyList())
    val customStations: StateFlow<List<RadioStation>> = _custom.asStateFlow()

    private val _editVersion = MutableStateFlow(0)
    val editVersion: StateFlow<Int> = _editVersion.asStateFlow()

    init {
        readList("overrides").forEach { overrides[it.id] = it }
        _favorites.value = readList("favorites").map { applyOverride(it) }
        _recents.value = readList("recents").map { applyOverride(it) }
        _custom.value = readList("custom").map { applyOverride(it) }
        RadioApi.CURATED_TUNISIA.forEach { known[it.id] = applyOverride(it) }
        _favorites.value.forEach { known[it.id] = it }
        _recents.value.forEach { known[it.id] = it }
        _custom.value.forEach { known[it.id] = it }
        overrides.values.forEach { known[it.id] = it }
    }

    private fun applyOverride(station: RadioStation): RadioStation {
        val ov = overrides[station.id] ?: return station
        return station.copy(
            name = ov.name.ifBlank { station.name },
            streamUrl = ov.streamUrl.ifBlank { station.streamUrl },
            customFavicon = ov.customFavicon,
            favicon = ov.favicon ?: station.favicon,
        )
    }

    fun getStation(id: String): RadioStation? = known[id]?.let { applyOverride(it) } ?: overrides[id]

    fun rememberStations(stations: List<RadioStation>) {
        stations.forEach { known[it.id] = applyOverride(it) }
    }

    fun adoptLogoUri(uri: android.net.Uri): String? = runCatching {
        val dir = File(context.filesDir, "radio-logos").apply { mkdirs() }
        val target = File(dir, "logo_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        if (target.exists() && target.length() > 0L) target.absolutePath else null
    }.getOrNull()

    fun updateStation(
        stationId: String,
        name: String,
        streamUrl: String,
        customFavicon: String?,
    ): RadioStation? {
        val base = getStation(stationId) ?: return null
        val updated = base.copy(
            name = name.trim().ifBlank { base.name },
            streamUrl = streamUrl.trim().ifBlank { base.streamUrl },
            customFavicon = customFavicon?.trim()?.takeIf { it.isNotBlank() },
        )
        overrides[stationId] = updated
        known[stationId] = updated
        saveList("overrides", overrides.values.toList())

        if (_favorites.value.any { it.id == stationId }) {
            val nextFav = _favorites.value.map { if (it.id == stationId) updated else it }
            _favorites.value = nextFav
            saveList("favorites", nextFav)
        }
        if (_recents.value.any { it.id == stationId }) {
            val nextRec = _recents.value.map { if (it.id == stationId) updated else it }
            _recents.value = nextRec
            saveList("recents", nextRec)
        }
        if (_custom.value.any { it.id == stationId }) {
            val nextCus = _custom.value.map { if (it.id == stationId) updated else it }
            _custom.value = nextCus
            saveList("custom", nextCus)
        }
        _editVersion.value = _editVersion.value + 1
        return updated
    }

    fun isFavorite(stationId: String): Boolean = _favorites.value.any { it.id == stationId }

    fun toggleFavorite(station: RadioStation) {
        val eff = applyOverride(station)
        known[eff.id] = eff
        val current = _favorites.value
        val updated = if (current.any { it.id == eff.id }) {
            current.filterNot { it.id == eff.id }
        } else {
            listOf(eff) + current
        }
        _favorites.value = updated
        saveList("favorites", updated)
    }

    fun addRecent(station: RadioStation) {
        val eff = applyOverride(station)
        known[eff.id] = eff
        val updated = (listOf(eff) + _recents.value.filterNot { it.id == eff.id }).take(30)
        _recents.value = updated
        saveList("recents", updated)
    }

    fun addCustomStation(
        name: String,
        streamUrl: String,
        customFavicon: String? = null,
        country: String? = null,
        tags: String? = null,
    ): RadioStation {
        val station = RadioStation(
            id = "custom_${UUID.randomUUID()}",
            name = name.trim(),
            streamUrl = streamUrl.trim(),
            country = country?.trim()?.takeIf { it.isNotBlank() },
            tags = tags?.trim()?.takeIf { it.isNotBlank() },
            isCustom = true,
            customFavicon = customFavicon?.trim()?.takeIf { it.isNotBlank() },
        )
        known[station.id] = station
        val updatedCustom = listOf(station) + _custom.value
        _custom.value = updatedCustom
        saveList("custom", updatedCustom)
        val updatedFav = listOf(station) + _favorites.value
        _favorites.value = updatedFav
        saveList("favorites", updatedFav)
        return station
    }

    fun removeCustomStation(stationId: String) {
        overrides.remove(stationId)
        saveList("overrides", overrides.values.toList())
        val updatedCustom = _custom.value.filterNot { it.id == stationId }
        _custom.value = updatedCustom
        saveList("custom", updatedCustom)
        val updatedFav = _favorites.value.filterNot { it.id == stationId }
        _favorites.value = updatedFav
        saveList("favorites", updatedFav)
    }

    suspend fun loadCategory(category: RadioCategory): List<RadioStation> {
        val raw = when (category) {
            RadioCategory.FAVORITES -> (_favorites.value + _custom.value).distinctBy { it.id }
            RadioCategory.TUNISIA -> api.getTunisianStations()
            RadioCategory.TOP_WORLD -> api.getTopStations()
            RadioCategory.NEWS -> api.getStationsByTag("news")
            RadioCategory.MUSIC -> api.getStationsByTag("music")
            RadioCategory.QURAN -> {
                val remote = api.getStationsByTag("quran") + api.getStationsByTag("islamic")
                val localQuran = RadioApi.CURATED_TUNISIA.filter { it.tags?.contains("quran", ignoreCase = true) == true }
                (localQuran + remote).distinctBy { it.id }
            }
            RadioCategory.CULTURE -> api.getStationsByTag("culture")
            RadioCategory.SPORTS -> api.getStationsByTag("sport")
            RadioCategory.RECENTS -> _recents.value
        }
        val list = raw.map { applyOverride(it) }
        rememberStations(list)
        return list
    }

    suspend fun search(query: String): List<RadioStation> {
        val list = api.searchStations(query).map { applyOverride(it) }
        rememberStations(list)
        return list
    }

    private fun readList(key: String): List<RadioStation> = runCatching {
        val raw = prefs.getString(key, null) ?: return emptyList()
        val arr = JSONArray(raw)
        buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    RadioStation(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        streamUrl = o.optString("streamUrl"),
                        favicon = o.optString("favicon").takeIf { it.isNotBlank() },
                        country = o.optString("country").takeIf { it.isNotBlank() },
                        countryCode = o.optString("countryCode").takeIf { it.isNotBlank() },
                        state = o.optString("state").takeIf { it.isNotBlank() },
                        language = o.optString("language").takeIf { it.isNotBlank() },
                        tags = o.optString("tags").takeIf { it.isNotBlank() },
                        homepage = o.optString("homepage").takeIf { it.isNotBlank() },
                        codec = o.optString("codec").takeIf { it.isNotBlank() },
                        bitrate = o.optInt("bitrate", 0),
                        votes = o.optInt("votes", 0),
                        isCustom = o.optBoolean("isCustom", false),
                        customWebUrl = o.optString("customWebUrl").takeIf { it.isNotBlank() },
                        customFavicon = o.optString("customFavicon").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun saveList(key: String, list: List<RadioStation>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("streamUrl", s.streamUrl)
                    s.favicon?.let { put("favicon", it) }
                    s.country?.let { put("country", it) }
                    s.countryCode?.let { put("countryCode", it) }
                    s.state?.let { put("state", it) }
                    s.language?.let { put("language", it) }
                    s.tags?.let { put("tags", it) }
                    s.homepage?.let { put("homepage", it) }
                    s.codec?.let { put("codec", it) }
                    put("bitrate", s.bitrate)
                    put("votes", s.votes)
                    put("isCustom", s.isCustom)
                    s.customWebUrl?.let { put("customWebUrl", it) }
                    s.customFavicon?.let { put("customFavicon", it) }
                },
            )
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }
}

/**
 * [MusicSource] adapter for Online Radio stations so that Podium's [app.podium.sources.api.resolve.StreamResolver],
 * [app.podium.sources.api.ArtworkResolver], and Media3 service play radio stations natively with full
 * notification, lock-screen, and Click Wheel support.
 */
class RadioMusicSource(
    private val repository: RadioRepository,
    private val iconCacheDir: File,
) : MusicSource {

    override val descriptor = SourceDescriptor(
        id = RadioStation.RADIO_SOURCE_ID,
        displayName = "Radio",
        providerName = "Radio Browser",
        basis = Basis.OFFICIAL_API,
        environment = MusicEnvironment.ONLINE,
    )

    override val capabilities: StateFlow<SourceCapabilities> = MutableStateFlow(
        SourceCapabilities.available(Capability.DIRECT_STREAM, Capability.ARTWORK, Capability.LIVE_RADIO),
    )

    override val playback: PlaybackFacet = object : PlaybackFacet {
        override val routes: Set<PlaybackRoute> = setOf(PlaybackRoute.DIRECT)

        override suspend fun resolve(track: Track, quality: QualityRequest, purpose: Purpose): FacetResolution {
            if (purpose == Purpose.DOWNLOAD) {
                return FacetResolution.Miss(MissReason.NOT_PERMITTED)
            }
            val station = repository.getStation(track.source.providerKey)
            val rawUrl = station?.streamUrl?.takeIf { it.isNotBlank() }
                ?: track.source.providerUri?.takeIf { it.isNotBlank() }
                ?: return FacetResolution.Miss(MissReason.NOT_FOUND)
            val (resolvedUrl, resolvedMime) = resolveRadioStreamUrl(station, rawUrl)
            val claimed = station?.bitrate?.takeIf { it > 0 }?.let { AudioQuality(bitrateKbps = it) }
            return FacetResolution.Resolved(
                PlaybackTarget.DirectStream(
                    trackId = track.id,
                    media = PlayableMedia(
                        uri = resolvedUrl,
                        sourceId = RadioStation.RADIO_SOURCE_ID,
                        mimeType = resolvedMime,
                        claimedQuality = claimed,
                        headers = mapOf(
                            "User-Agent" to "Podium/1.0",
                            "Icy-MetaData" to "1",
                        ),
                        cacheKey = "${track.id.value}|radio",
                    ),
                ),
            )
        }
    }

    private suspend fun resolveRadioStreamUrl(station: RadioStation?, initialUrl: String): Pair<String, String?> = withContext(Dispatchers.IO) {
        val primary = probeStream(initialUrl)
        if (primary != null) return@withContext primary
        if (station != null && !station.isCustom) {
            val fallbackCandidates = runCatching {
                val query = station.name
                    .replace("100.6", "", ignoreCase = true)
                    .replace("FM", "", ignoreCase = true)
                    .replace("Radio", "", ignoreCase = true)
                    .replace("è", "e", ignoreCase = true)
                    .replace("é", "e", ignoreCase = true)
                    .trim()
                    .ifBlank { station.name }
                val byName = RadioApi().searchStations(query, limit = 8)
                if (byName.isNotEmpty()) byName else if (station.countryCode.equals("TN", ignoreCase = true)) {
                    RadioApi().getTunisianStations().filter {
                        it.id != station.id && it.name.contains(query.take(5), ignoreCase = true)
                    }
                } else emptyList()
            }.getOrDefault(emptyList())
            for (candidate in fallbackCandidates) {
                if (candidate.streamUrl.equals(initialUrl, ignoreCase = true)) continue
                val probed = probeStream(candidate.streamUrl)
                if (probed != null) return@withContext probed
            }
        }
        val isHls = initialUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        initialUrl to if (isHls) "application/x-mpegURL" else null
    }

    private fun probeStream(url: String, depth: Int = 0): Pair<String, String?>? {
        if (depth > 4) return null
        var currentUrl = url.trim()
        if (!currentUrl.startsWith("http://") && !currentUrl.startsWith("https://")) return null
        repeat(5) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 4_500
                    readTimeout = 4_500
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Podium/1.0")
                    setRequestProperty("Icy-MetaData", "1")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")?.trim()?.takeIf { it.isNotBlank() } ?: return null
                    currentUrl = URL(URL(currentUrl), location).toString()
                    return@repeat
                }
                if (code !in 200..299) return null
                val contentType = conn.contentType?.lowercase().orEmpty()
                val cleanPath = currentUrl.substringBefore('?').lowercase()
                if (cleanPath.endsWith(".pls") || contentType.contains("scpls")) {
                    val text = conn.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(4096)
                        val n = r.read(buf)
                        if (n > 0) String(buf, 0, n) else ""
                    }
                    val extracted = text.lineSequence()
                        .map { it.trim() }
                        .firstOrNull { it.startsWith("File1=", ignoreCase = true) || it.startsWith("http://") || it.startsWith("https://") }
                        ?.substringAfter("=")
                        ?.trim()
                    if (!extracted.isNullOrBlank()) return probeStream(extracted, depth + 1) ?: (extracted to null)
                }
                if (cleanPath.endsWith(".m3u8") || contentType.contains("vnd.apple.mpegurl") || contentType.contains("x-mpegurl")) {
                    val head = conn.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(2048)
                        val n = r.read(buf)
                        if (n > 0) String(buf, 0, n) else ""
                    }
                    if (head.contains("#EXT-X-", ignoreCase = true) || cleanPath.endsWith(".m3u8")) {
                        return currentUrl to "application/x-mpegURL"
                    }
                    val firstHttp = head.lineSequence()
                        .map { it.trim() }
                        .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
                    if (!firstHttp.isNullOrBlank()) {
                        return probeStream(firstHttp, depth + 1) ?: (firstHttp to null)
                    }
                }
                if (cleanPath.endsWith(".m3u")) {
                    val head = conn.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(2048)
                        val n = r.read(buf)
                        if (n > 0) String(buf, 0, n) else ""
                    }
                    val firstHttp = head.lineSequence()
                        .map { it.trim() }
                        .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
                    if (!firstHttp.isNullOrBlank()) {
                        return probeStream(firstHttp, depth + 1) ?: (firstHttp to null)
                    }
                }
                return currentUrl to null
            } catch (_: Exception) {
                return null
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    override val artwork: ArtworkFacet = object : ArtworkFacet {
        override suspend fun load(ref: ArtworkRef, sizePx: Int): ArtworkPayload? = withContext(Dispatchers.IO) {
            val rawTarget = when {
                ref.key.startsWith("/") || ref.key.startsWith("file://") || ref.key.startsWith("http://") || ref.key.startsWith("https://") -> ref.key
                else -> repository.getStation(ref.key)?.displayFavicon
            } ?: return@withContext null

            if (rawTarget.startsWith("/") || rawTarget.startsWith("file://")) {
                val localFile = if (rawTarget.startsWith("file://")) File(java.net.URI(rawTarget)) else File(rawTarget)
                if (localFile.exists() && localFile.length() > 0L) {
                    return@withContext ArtworkPayload.LocalFile(localFile.absolutePath)
                }
                return@withContext null
            }

            if (!rawTarget.startsWith("http://") && !rawTarget.startsWith("https://")) {
                return@withContext null
            }

            iconCacheDir.mkdirs()
            val safeKey = rawTarget.hashCode().toUInt().toString(16)
            val cached = File(iconCacheDir, "favicon_$safeKey.img")
            if (cached.exists() && cached.length() > 0L) {
                return@withContext ArtworkPayload.LocalFile(cached.absolutePath)
            }
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(rawTarget).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Podium/1.0")
                }
                if (conn.responseCode in 200..299) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (bytes.isNotEmpty()) {
                        cached.writeBytes(bytes)
                        return@withContext ArtworkPayload.LocalFile(cached.absolutePath)
                    }
                }
            } catch (_: Exception) {
                // Ignore broken station favicons
            } finally {
                conn?.disconnect()
            }
            null
        }
    }

    override val catalog: CatalogFacet = object : CatalogFacet {
        override suspend fun search(query: SearchQuery): Outcome<SearchResults> =
            Outcome.Success(SearchResults())

        override suspend fun track(ref: SourceRef): Outcome<Track> {
            val station = repository.getStation(ref.providerKey)
                ?: return Outcome.Failure(PodiumError.NotFound(ref.providerKey))
            return Outcome.Success(station.toPodiumTrack())
        }

        override suspend fun album(id: AlbumId): Outcome<AlbumDetail> =
            Outcome.Failure(PodiumError.NotFound(id.value))

        override suspend fun artist(id: ArtistId): Outcome<ArtistDetail> =
            Outcome.Failure(PodiumError.NotFound(id.value))
    }
}
