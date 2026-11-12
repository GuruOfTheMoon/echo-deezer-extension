package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.AlternativeVersions
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.api.SpotifyClient
import dev.brahmkshatriya.echo.extension.api.SpotifyPlaylist
import dev.brahmkshatriya.echo.extension.api.SpotifyTrack
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/**
 * Reads a public Spotify playlist and recreates it as a Deezer playlist by
 * searching the public Deezer API for the best matching track of each entry.
 */
class SpotifyPlaylistImporter(
    private val extension: DeezerExtension,
    private val api: DeezerApi,
    private val spotify: SpotifyClient = SpotifyClient(),
) {

    data class ImportResult(
        val playlistId: String,
        val playlistTitle: String,
        val spotifyName: String,
        val totalTracks: Int,
        val matchedTracks: Int,
        val notFound: List<String>,
    )

    suspend fun import(
        linkOrId: String,
        onStatus: suspend (String) -> Unit = {},
    ): ImportResult {
        extension.handleArlExpiration()

        val spotifyPlaylist = spotify.loadPlaylist(linkOrId) { loaded, total ->
            onStatus("Reading Spotify playlist: $loaded/$total tracks")
        }
        val tracks = spotifyPlaylist.tracks
        if (tracks.isEmpty()) {
            throw Exception("The Spotify playlist does not contain any playable tracks.")
        }

        onStatus("Matching ${tracks.size} Spotify tracks on Deezer\u2026")
        val matches = arrayOfNulls<DeezerCandidate>(tracks.size)
        val notFound = mutableListOf<String>()
        val stateMutex = Mutex()
        var processed = 0
        val progressStep = (tracks.size / 4).coerceAtLeast(10)

        coroutineScope {
            val semaphore = Semaphore(MATCH_CONCURRENCY)
            tracks.forEachIndexed { index, track ->
                launch {
                    semaphore.withPermit {
                        val match = findMatch(track)
                        val progress = stateMutex.withLock {
                            if (match == null) {
                                if (notFound.size < MAX_REPORTED_MISSES) {
                                    notFound += track.displayName
                                }
                            } else {
                                matches[index] = match
                            }
                            processed++
                            if (processed % progressStep == 0 || processed == tracks.size) {
                                "Matched $processed/${tracks.size} tracks\u2026"
                            } else null
                        }
                        progress?.let { onStatus(it) }
                    }
                }
            }
        }

        val matched = matches.filterNotNull()
        if (matched.isEmpty()) {
            throw Exception("None of the Spotify tracks could be found on Deezer.")
        }

        val playlistTitle = spotifyPlaylist.name.take(MAX_PLAYLIST_TITLE_LENGTH)
            .ifBlank { "Imported Spotify Playlist" }
        val playlistDescription = buildDescription(spotifyPlaylist)

        onStatus("Creating Deezer playlist \"$playlistTitle\"\u2026")
        val created = api.createPlaylist(playlistTitle, playlistDescription)
        created.errorMessage()?.let {
            throw Exception("Could not create the Deezer playlist: $it")
        }
        val playlistId = created.text("results")?.takeIf { it.isNotBlank() }
            ?: throw Exception("Deezer did not return the new playlist id.")

        val deezerPlaylist = Playlist(
            id = playlistId,
            title = playlistTitle,
            description = playlistDescription,
            isEditable = true
        )

        try {
            matched.chunked(ADD_CHUNK_SIZE).forEachIndexed { index, chunk ->
                val response = api.addToPlaylist(
                    deezerPlaylist,
                    chunk.map { Track(id = it.id, title = it.fullTitle) }
                )
                response.errorMessage()?.let {
                    throw Exception("Could not add tracks to the Deezer playlist: $it")
                }
                val added = minOf((index + 1) * ADD_CHUNK_SIZE, matched.size)
                onStatus("Adding tracks: $added/${matched.size}\u2026")
            }
        } catch (throwable: Throwable) {
            runCatching { api.deletePlaylist(playlistId) }
            throw throwable
        }

        return ImportResult(
            playlistId = playlistId,
            playlistTitle = playlistTitle,
            spotifyName = spotifyPlaylist.name,
            totalTracks = tracks.size,
            matchedTracks = matched.size,
            notFound = notFound
        )
    }

    private suspend fun findMatch(track: SpotifyTrack): DeezerCandidate? {
        val title = track.title.trim()
        if (title.isEmpty()) return null
        val artists = track.artists.map { it.trim() }.filter { it.isNotEmpty() }
        val primaryArtist = artists.firstOrNull()
        val baseTitle = AlternativeVersions.baseTitle(title)

        val queries = linkedSetOf<String>()
        if (primaryArtist != null) {
            queries += "$title $primaryArtist"
            if (baseTitle.isNotBlank() && baseTitle != AlternativeVersions.normalize(title)) {
                queries += "$baseTitle $primaryArtist"
            }
        } else {
            queries += title
            if (baseTitle.isNotBlank() && baseTitle != AlternativeVersions.normalize(title)) {
                queries += baseTitle
            }
        }
        if (artists.size > 1) {
            val allArtists = artists.joinToString(" ")
            queries += "$title $allArtists"
            if (baseTitle.isNotBlank() && baseTitle != AlternativeVersions.normalize(title)) {
                queries += "$baseTitle $allArtists"
            }
        }

        val candidates = LinkedHashMap<String, DeezerCandidate>()
        for (query in queries) {
            searchCandidates(query).forEach { candidates.putIfAbsent(it.id, it) }
            if (candidates.size >= MAX_CANDIDATES) break
        }
        return TrackMatcher.best(track, candidates.values.toList())
    }

    private suspend fun searchCandidates(query: String): List<DeezerCandidate> {
        repeat(SEARCH_ATTEMPTS) { attempt ->
            val response = runCatching {
                api.callPublicApi(
                    "search",
                    mapOf("q" to query, "limit" to SEARCH_LIMIT.toString())
                )
            }.getOrNull()

            if (response != null) {
                val data = response["data"] as? JsonArray ?: return emptyList()
                return data.mapNotNull { (it as? JsonObject)?.toCandidate() }
            }
            if (attempt < SEARCH_ATTEMPTS - 1) delay(750)
        }
        return emptyList()
    }

    private fun buildDescription(playlist: SpotifyPlaylist): String {
        val description = buildString {
            append("Imported from Spotify: https://open.spotify.com/playlist/")
            append(playlist.id)
            playlist.owner?.takeIf { it.isNotBlank() }?.let {
                append("\nCreated by ").append(it)
            }
            playlist.description?.takeIf { it.isNotBlank() }?.let {
                append("\n\n").append(it)
            }
        }
        return description.take(MAX_PLAYLIST_DESCRIPTION_LENGTH)
    }

    private companion object {
        private const val MATCH_CONCURRENCY = 3
        private const val SEARCH_ATTEMPTS = 2
        private const val SEARCH_LIMIT = 10
        private const val MAX_CANDIDATES = 30
        private const val MAX_REPORTED_MISSES = 50
        private const val ADD_CHUNK_SIZE = 100
        private const val MAX_PLAYLIST_TITLE_LENGTH = 100
        private const val MAX_PLAYLIST_DESCRIPTION_LENGTH = 1_000
    }
}

internal data class DeezerCandidate(
    val id: String,
    val title: String,
    val titleVersion: String,
    val artist: String,
    val durationSeconds: Long?,
    val album: String?,
    val readable: Boolean,
) {
    val fullTitle: String
        get() = if (
            titleVersion.isNotBlank() &&
            !title.contains(titleVersion, ignoreCase = true)
        ) {
            "$title $titleVersion"
        } else {
            title
        }
}

internal object TrackMatcher {

    private const val MIN_SCORE = 5.0
    private const val MIN_TITLE_SIMILARITY = 0.72
    private const val MIN_ARTIST_SIMILARITY = 0.5

    fun best(expected: SpotifyTrack, candidates: List<DeezerCandidate>): DeezerCandidate? {
        var best: DeezerCandidate? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (candidate in candidates) {
            val score = score(expected, candidate)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best?.takeIf { bestScore >= MIN_SCORE }
    }

    fun score(expected: SpotifyTrack, candidate: DeezerCandidate): Double {
        val expectedBase = AlternativeVersions.baseTitle(expected.title)
        val candidateBase = AlternativeVersions.baseTitle(candidate.fullTitle)
        val titleSimilarity = AlternativeVersions.similarity(expectedBase, candidateBase)
        if (titleSimilarity < MIN_TITLE_SIMILARITY) return -1.0

        val artistSimilarity = bestArtistSimilarity(expected.artists, candidate.artist)
        if (artistSimilarity < MIN_ARTIST_SIMILARITY) return -1.0

        val expectedKind = AlternativeVersions.kind(expected.title)
        val candidateKind = AlternativeVersions.kind(candidate.fullTitle)
        if (expectedKind != null && candidateKind == null) {
            // Never replace a remix/live version with the plain original.
            return -1.0
        }

        var score = titleSimilarity * 6.0 + artistSimilarity * 4.0

        val expectedDuration = expected.durationMs
        val candidateDuration = candidate.durationSeconds?.times(1000)
        if (expectedDuration != null && candidateDuration != null) {
            score += when (val delta = abs(expectedDuration - candidateDuration)) {
                in 0L..2_000L -> 1.5
                in 2_001L..5_000L -> 1.0
                in 5_001L..12_000L -> 0.4
                in 30_000L..Long.MAX_VALUE -> -1.5
                else -> 0.0
            }
        }

        score += when {
            expectedKind == null && candidateKind == null -> 1.0
            expectedKind != null && candidateKind == expectedKind -> 1.0
            expectedKind != null && candidateKind != null -> -1.5
            else -> -2.0
        }

        if (candidate.readable) score += 0.5
        if (
            AlternativeVersions.normalize(expected.title) ==
            AlternativeVersions.normalize(candidate.fullTitle)
        ) {
            score += 0.5
        }
        return score
    }

    private fun bestArtistSimilarity(
        expectedArtists: List<String>,
        candidateArtist: String,
    ): Double {
        val candidate = AlternativeVersions.normalize(candidateArtist)
        if (candidate.isBlank()) return 0.0
        return expectedArtists.maxOfOrNull { artist ->
            val normalized = AlternativeVersions.normalize(artist)
            if (normalized.isBlank()) {
                0.0
            } else if (normalized == candidate) {
                1.0
            } else {
                val shorter = minOf(normalized.length, candidate.length)
                if (shorter >= 4 && (candidate.contains(normalized) || normalized.contains(candidate))) {
                    0.95
                } else {
                    AlternativeVersions.similarity(normalized, candidate) * 0.9
                }
            }
        } ?: 0.0
    }
}

private fun JsonObject.toCandidate(): DeezerCandidate? {
    val id = text("id")?.takeIf { it.isNotBlank() } ?: return null
    val title = text("title")?.takeIf { it.isNotBlank() } ?: return null
    val version = text("title_version").orEmpty()
    val artist = (this["artist"] as? JsonObject)?.text("name")?.takeIf { it.isNotBlank() }
        ?: return null
    val duration = text("duration")?.toLongOrNull()
    val album = (this["album"] as? JsonObject)?.text("title")
    val readable = text("readable")?.toBooleanStrictOrNull() ?: true

    return DeezerCandidate(
        id = id,
        title = title,
        titleVersion = version,
        artist = artist,
        durationSeconds = duration,
        album = album,
        readable = readable
    )
}

private fun JsonObject.text(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return (element as? JsonPrimitive)?.content?.takeUnless { it == "null" }
}

/**
 * Returns the error message of a Deezer API response, or null when the
 * response is successful. Deezer reports success with an empty `error` field
 * (`[]`, `{}` or `""`), so an empty error must not be treated as a failure.
 */
internal fun JsonObject.errorMessage(): String? {
    val error = this["error"] ?: return null
    if (error is JsonNull) return null
    if (error is JsonPrimitive) {
        return error.content.takeIf { it.isNotBlank() && it != "false" && it != "0" }
    }
    if (error is JsonArray) return if (error.isEmpty()) null else error.toString()
    val objectError = error as? JsonObject ?: return error.toString()
    if (objectError.isEmpty()) return null
    return objectError.text("message")
        ?: objectError.text("VALID_TOKEN_REQUIRED")
        ?: objectError.text("error")
        ?: objectError.toString()
}
