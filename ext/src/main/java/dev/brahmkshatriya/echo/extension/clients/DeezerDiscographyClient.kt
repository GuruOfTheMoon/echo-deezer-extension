package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Date as EchoDate
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerParser
import dev.brahmkshatriya.echo.extension.Utils.asObject
import dev.brahmkshatriya.echo.extension.Utils.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.jsonArray
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds a synthetic "All Songs" playlist from an artist's complete Deezer
 * discography (albums, EPs and singles).
 *
 * The releases and their tracks are fetched through the public Deezer API and
 * cached in memory for the lifetime of the extension session. Tracks are
 * deduplicated across standard/deluxe editions by ISRC, falling back to
 * title + duration when an ISRC is unavailable.
 */
class DeezerDiscographyClient(
    private val api: DeezerApi,
    private val parser: DeezerParser
) {

    private val releaseCache = ConcurrentHashMap<String, List<Release>>()
    private val trackCache = ConcurrentHashMap<String, List<Track>>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val requestSemaphore = Semaphore(CONCURRENCY)

    fun isVirtual(playlist: Playlist): Boolean {
        return playlist.extras[VIRTUAL_KEY] == VIRTUAL_VALUE ||
                playlist.id.startsWith(VIRTUAL_ID_PREFIX)
    }

    fun artistId(playlist: Playlist): String? {
        return playlist.extras["artist_id"]?.takeIf { it.isNotBlank() }
            ?: playlist.id.removePrefix(VIRTUAL_ID_PREFIX)
                .takeIf { it != playlist.id && it.isNotBlank() }
    }

    fun createVirtualPlaylist(artist: Artist): Playlist {
        return Playlist(
            id = VIRTUAL_ID_PREFIX + artist.id,
            title = ALL_SONGS_TITLE,
            subtitle = "${artist.name} • Complete Discography",
            description = "Every track from ${artist.name}'s albums, EPs and singles.",
            cover = artist.cover,
            authors = listOf(artist),
            isEditable = false,
            isPrivate = false,
            isSaveable = false,
            isLikeable = false,
            isShareable = false,
            isRadioSupported = false,
            extras = mapOf(
                VIRTUAL_KEY to VIRTUAL_VALUE,
                "artist_id" to artist.id,
                "artist_name" to artist.name
            )
        )
    }

    suspend fun loadPlaylist(playlist: Playlist): Playlist {
        if (!isVirtual(playlist)) return playlist
        val artistId = artistId(playlist) ?: return playlist
        if (playlist.authors.isNotEmpty()) return playlist
        return playlist.copy(
            authors = listOf(
                Artist(
                    id = artistId,
                    name = playlist.extras["artist_name"] ?: "Artist",
                    cover = playlist.cover
                )
            )
        )
    }

    fun loadTracks(playlist: Playlist): Feed<Track> {
        val artistId = artistId(playlist) ?: return emptyList<Track>().toFeed()
        val artist = playlist.authors.firstOrNull()?.copy(id = artistId)
            ?: Artist(
                id = artistId,
                name = playlist.extras["artist_name"] ?: "Artist",
                cover = playlist.cover
            )
        return getDiscographyFeed(artist, playlist.cover)
    }

    fun getDiscographyFeed(artist: Artist, cover: ImageHolder? = artist.cover): Feed<Track> {
        val pagedData = PagedData.Continuous { continuation ->
            val releases = getReleases(artist)
            val start = continuation?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            if (start >= releases.size) {
                Page(emptyList(), null)
            } else {
                val end = (start + RELEASES_PER_PAGE).coerceAtMost(releases.size)
                val tracks = loadTracksRange(artist, releases, start, end)
                Page(tracks, if (end < releases.size) end.toString() else null)
            }
        }
        return pagedData.toFeed(background = cover)
    }

    private suspend fun getReleases(artist: Artist): List<Release> {
        releaseCache[artist.id]?.let { return it }
        return mutexFor("releases:${artist.id}").withLock {
            releaseCache[artist.id] ?: fetchReleases(artist.id).also { releaseCache[artist.id] = it }
        }
    }

    private suspend fun fetchReleases(artistId: String): List<Release> {
        val releases = mutableListOf<Release>()
        val seenIds = mutableSetOf<String>()
        var index = 0
        var total: Int? = null

        while (index < MAX_RELEASES) {
            val result = api.artistAlbums(artistId, index, ALBUM_PAGE_SIZE)
            val data = result["data"]?.jsonArray ?: break
            if (data.isEmpty()) break

            data.forEach { element ->
                val obj = element.asObject()
                val id = obj.str("id") ?: return@forEach
                if (!seenIds.add(id)) return@forEach
                releases += Release(
                    id = id,
                    title = obj.str("title").orEmpty(),
                    releaseDate = obj.str("release_date"),
                    md5 = obj.str("md5_image"),
                    recordType = obj.str("record_type"),
                    index = releases.size
                )
            }

            index += data.size
            total = result.str("total")?.toIntOrNull() ?: total
            if (total != null && index >= total) break
            if (data.size < ALBUM_PAGE_SIZE) break
        }

        // Oldest releases first: this keeps the discography in chronological
        // order and makes the original edition win over later reissues when
        // duplicate recordings are removed. The original API order is kept
        // for releases with the same date.
        return releases.sortedWith(
            compareBy<Release> { it.releaseDate ?: "9999-99-99" }.thenBy { it.index }
        )
    }

    /**
     * Loads the tracks of releases [start, end), but also walks all previous
     * releases so that duplicates across page boundaries are removed
     * deterministically. Previous releases are already cached, so this does
     * not cause extra network requests during normal paging.
     */
    private suspend fun loadTracksRange(
        artist: Artist,
        releases: List<Release>,
        start: Int,
        end: Int
    ): List<Track> {
        val tracksByRelease = supervisorScope {
            releases.subList(0, end).map { release ->
                async(Dispatchers.IO) {
                    requestSemaphore.withPermit {
                        try {
                            getAlbumTracks(artist, release)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                }
            }.awaitAll()
        }

        val seenIds = HashSet<String>()
        val seenKeys = HashSet<String>()
        val tracks = mutableListOf<Track>()

        for (index in 0 until end) {
            for (track in tracksByRelease[index]) {
                if (!seenIds.add(track.id)) continue
                val key = dedupeKey(track)
                if (!key.isNullOrEmpty() && !seenKeys.add(key)) continue
                if (index >= start) tracks += track
            }
        }
        return tracks
    }

    private suspend fun getAlbumTracks(artist: Artist, release: Release): List<Track> {
        val key = "${artist.id}:${release.id}"
        trackCache[key]?.let { return it }
        return mutexFor("tracks:$key").withLock {
            trackCache[key] ?: fetchAlbumTracks(artist, release).also { trackCache[key] = it }
        }
    }

    private suspend fun fetchAlbumTracks(artist: Artist, release: Release): List<Track> {
        val tracks = mutableListOf<Track>()
        val releaseDate = parseDate(release.releaseDate)
        val releaseCover = parser.getCover(release.md5, "cover")
        val album = Album(
            id = release.id,
            title = release.title,
            type = release.albumType(),
            cover = releaseCover,
            artists = listOf(artist),
            releaseDate = releaseDate
        )

        var index = 0
        var total: Int? = null
        while (index < MAX_TRACKS_PER_RELEASE) {
            val result = api.albumTracks(release.id, index, TRACK_PAGE_SIZE)
            val data = result["data"]?.jsonArray ?: break
            if (data.isEmpty()) break

            data.forEach { element ->
                val obj = element.asObject()
                val id = obj.str("id") ?: return@forEach
                val trackArtistObject = runCatching { obj["artist"]?.asObject() }.getOrNull()
                val trackArtist = Artist(
                    id = trackArtistObject?.str("id") ?: artist.id,
                    name = trackArtistObject?.str("name") ?: artist.name,
                    cover = artist.cover
                )
                val title = obj.str("title").orEmpty()
                    .ifBlank { obj.str("title_short").orEmpty() }
                val cover = parser.getCover(obj.str("md5_image") ?: release.md5, "cover")
                    ?: releaseCover

                tracks += Track(
                    id = id,
                    title = title,
                    cover = cover,
                    artists = listOf(trackArtist),
                    album = album.copy(artists = listOf(trackArtist)),
                    duration = obj.str("duration")?.toLongOrNull()?.times(1000),
                    releaseDate = releaseDate,
                    isrc = obj.str("isrc")?.takeIf { it.isNotBlank() },
                    albumOrderNumber = obj.str("track_position")?.toLongOrNull(),
                    albumDiscNumber = obj.str("disk_number")?.toLongOrNull(),
                    isExplicit = obj.str("explicit_lyrics") == "true",
                    isPlayable = if (obj.str("readable") == "false") {
                        Track.Playable.No("Not readable in your region")
                    } else {
                        Track.Playable.Yes
                    },
                    extras = mapOf(
                        "album_id" to release.id,
                        VIRTUAL_KEY to VIRTUAL_VALUE,
                        "TRACK_TOKEN" to "",
                        "FALLBACK_ID" to "",
                        "FILESIZE_MP3_MISC" to "0",
                        "TYPE" to "cover"
                    )
                )
            }

            index += data.size
            total = result.str("total")?.toIntOrNull() ?: total
            if (total != null && index >= total) break
            if (data.size < TRACK_PAGE_SIZE) break
        }
        return tracks
    }

    /**
     * ISRC is the most reliable identifier for the same recording across
     * standard and deluxe editions. When it is missing, title + duration is
     * used as a best-effort fallback.
     */
    private fun dedupeKey(track: Track): String? {
        val isrc = track.isrc?.trim()?.uppercase(Locale.ROOT)
        if (!isrc.isNullOrEmpty()) return "isrc:$isrc"

        val title = track.title.lowercase(Locale.ROOT)
            .replace(WHITESPACE_REGEX, " ")
            .trim()
        if (title.isEmpty()) return null

        val duration = track.duration?.div(1000)
        return if (duration != null) "title:$title|duration:$duration" else "title:$title"
    }

    private fun parseDate(raw: String?): EchoDate? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val parts = raw.substringBefore(" ").split("-")
            EchoDate(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }.getOrNull()
    }

    private fun Release.albumType(): Album.Type? {
        return when (recordType?.lowercase(Locale.ROOT)) {
            "album" -> Album.Type.LP
            "single" -> Album.Type.Single
            "ep" -> Album.Type.EP
            "compile", "compilation" -> Album.Type.Compilation
            else -> null
        }
    }

    private fun mutexFor(key: String): Mutex {
        return locks.computeIfAbsent(key) { Mutex() }
    }

    private data class Release(
        val id: String,
        val title: String,
        val releaseDate: String?,
        val md5: String?,
        val recordType: String?,
        val index: Int
    )

    private companion object {
        private const val VIRTUAL_ID_PREFIX = "deezer-all-songs-"
        private const val VIRTUAL_KEY = "deezer_virtual"
        private const val VIRTUAL_VALUE = "artist_discography"
        private const val ALL_SONGS_TITLE = "All Songs"

        private const val ALBUM_PAGE_SIZE = 100
        private const val TRACK_PAGE_SIZE = 100
        private const val RELEASES_PER_PAGE = 10

        private const val CONCURRENCY = 4
        private const val MAX_RELEASES = 5000
        private const val MAX_TRACKS_PER_RELEASE = 2000

        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
