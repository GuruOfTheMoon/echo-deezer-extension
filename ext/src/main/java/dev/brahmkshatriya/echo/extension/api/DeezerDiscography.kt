package dev.brahmkshatriya.echo.extension.api

import dev.brahmkshatriya.echo.extension.DeezerApi
import kotlinx.serialization.json.JsonObject

/**
 * Wrapper around the public Deezer API endpoints needed to build a complete
 * artist discography.
 *
 * The internal gateway (`deezer.pageArtist`) only exposes a limited album
 * shelf, so the public API is used to page through every release and every
 * track of a release.
 */
class DeezerDiscography(private val deezerApi: DeezerApi) {

    suspend fun artistAlbums(artistId: String, index: Int, limit: Int): JsonObject {
        return deezerApi.callPublicApi(
            path = "artist/$artistId/albums",
            params = mapOf(
                "limit" to limit.toString(),
                "index" to index.toString()
            )
        )
    }

    suspend fun albumTracks(albumId: String, index: Int, limit: Int): JsonObject {
        return deezerApi.callPublicApi(
            path = "album/$albumId/tracks",
            params = mapOf(
                "limit" to limit.toString(),
                "index" to index.toString()
            )
        )
    }
}
