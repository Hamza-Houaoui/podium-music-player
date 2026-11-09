package app.podium.radio

import app.podium.core.model.ArtistCredit
import app.podium.core.model.ArtworkRef
import app.podium.core.model.Availability
import app.podium.core.model.Explicitness
import app.podium.core.model.MediaKind
import app.podium.core.model.PlaybackRoute
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import kotlinx.serialization.Serializable

@Serializable
data class RadioStation(
    val id: String,
    val name: String,
    val streamUrl: String,
    val favicon: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
    val state: String? = null,
    val language: String? = null,
    val tags: String? = null,
    val homepage: String? = null,
    val codec: String? = null,
    val bitrate: Int = 0,
    val votes: Int = 0,
    val isCustom: Boolean = false,
    val customWebUrl: String? = null,
    val customFavicon: String? = null,
) {
    val displayFavicon: String?
        get() = customFavicon?.takeIf { it.isNotBlank() } ?: favicon?.takeIf { it.isNotBlank() }

    val subtitle: String
        get() {
            val parts = mutableListOf<String>()
            if (!country.isNullOrBlank()) parts.add(country)
            val firstTag = tags?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            if (firstTag != null && !firstTag.equals(country, ignoreCase = true)) {
                parts.add(firstTag.replaceFirstChar { it.uppercase() })
            }
            if (bitrate > 0) parts.add("${bitrate} kbps")
            return if (parts.isNotEmpty()) parts.joinToString(" · ") else "Live Radio"
        }

    fun toPodiumTrack(): Track {
        val trackId = TrackId.of(RADIO_SOURCE_ID, id)
        val iconRef = displayFavicon?.takeIf {
            it.startsWith("http://") || it.startsWith("https://") || it.startsWith("/") || it.startsWith("file://")
        }
        return Track(
            id = trackId,
            title = name,
            artists = listOf(ArtistCredit(subtitle)),
            album = null,
            durationMs = null,
            trackNumber = null,
            discNumber = null,
            explicitness = Explicitness.NOT_EXPLICIT,
            artwork = iconRef?.let { ArtworkRef(RADIO_SOURCE_ID, it) },
            source = SourceRef(
                sourceId = RADIO_SOURCE_ID,
                providerKey = id,
                providerUri = streamUrl,
            ),
            availability = Availability.Playable,
            routes = setOf(PlaybackRoute.DIRECT),
            kind = MediaKind.LIVE,
        )
    }

    companion object {
        val RADIO_SOURCE_ID = SourceId("radio")
    }
}

enum class RadioCategory(val id: String, val title: String, val tag: String? = null) {
    FAVORITES("favorites", "Favorites"),
    TUNISIA("tunisia", "Tunisia"),
    TOP_WORLD("top_world", "Top World"),
    NEWS("news", "News", tag = "news"),
    MUSIC("music", "Music", tag = "music"),
    QURAN("quran", "Quran", tag = "quran"),
    CULTURE("culture", "Culture", tag = "culture"),
    SPORTS("sports", "Sports", tag = "sport"),
    RECENTS("recents", "Recently played");

    val label: String get() = title

    companion object {
        fun fromId(id: String): RadioCategory? = entries.firstOrNull { it.id == id }
    }
}
