package app.podium.sources.api

import app.podium.core.model.ArtworkRef

/**
 * Resolves `podium-art://` URIs to bytes or a local file through the owning source's ArtworkFacet.
 * Used by both the UI image loader and the media session's notification artwork, so neither ever
 * handles provider URLs or credentials.
 */
class ArtworkResolver(
    private val registry: SourceRegistry,
    private val offlineLookup: ((ArtworkRef) -> ArtworkPayload?)? = null,
) {
    suspend fun load(uri: String, sizePx: Int): ArtworkPayload? {
        val ref = ArtworkRef.parse(uri) ?: return null
        offlineLookup?.invoke(ref)?.let { return it }
        val facet = registry.get(ref.sourceId)?.artwork ?: return null
        return runCatching { facet.load(ref, sizePx) }.getOrNull()
    }
}
