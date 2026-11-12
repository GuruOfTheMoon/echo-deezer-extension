package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.extension.api.SpotifyTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMatcherTest {

    private fun spotify(
        title: String,
        vararg artists: String,
        durationMs: Long = 200_000L,
    ) = SpotifyTrack(
        title = title,
        artists = artists.toList(),
        durationMs = durationMs
    )

    private fun candidate(
        id: String = "1",
        title: String,
        version: String = "",
        artist: String,
        duration: Long = 200L,
        readable: Boolean = true,
    ) = DeezerCandidate(
        id = id,
        title = title,
        titleVersion = version,
        artist = artist,
        durationSeconds = duration,
        album = null,
        readable = readable
    )

    @Test
    fun prefersExactMatch() {
        val expected = spotify("Blinding Lights", "The Weeknd")
        val exact = candidate(id = "exact", title = "Blinding Lights", artist = "The Weeknd")
        val other = candidate(id = "other", title = "Blinding Lights", artist = "Someone Else")

        assertEquals(exact, TrackMatcher.best(expected, listOf(other, exact)))
        assertTrue(TrackMatcher.score(expected, exact) > 10.0)
    }

    @Test
    fun rejectsWrongArtist() {
        val expected = spotify("Blinding Lights", "The Weeknd")
        val wrongArtist = candidate(title = "Blinding Lights", artist = "Some Other Band")

        assertNull(TrackMatcher.best(expected, listOf(wrongArtist)))
        assertTrue(TrackMatcher.score(expected, wrongArtist) < 0.0)
    }

    @Test
    fun prefersOriginalOverRemix() {
        val expected = spotify("Blinding Lights", "The Weeknd")
        val original = candidate(id = "original", title = "Blinding Lights", artist = "The Weeknd")
        val remix = candidate(
            id = "remix",
            title = "Blinding Lights (Remix)",
            version = "(Remix)",
            artist = "The Weeknd"
        )

        assertEquals(original, TrackMatcher.best(expected, listOf(remix, original)))
    }

    @Test
    fun doesNotReplaceRemixWithOriginal() {
        val expected = spotify("Blinding Lights (Remix)", "The Weeknd")
        val original = candidate(title = "Blinding Lights", artist = "The Weeknd")

        assertNull(TrackMatcher.best(expected, listOf(original)))
    }

    @Test
    fun matchesRemixWhenItIsExpected() {
        val expected = spotify("Blinding Lights (Remix)", "The Weeknd")
        val remix = candidate(title = "Blinding Lights (Remix)", artist = "The Weeknd")

        assertEquals(remix, TrackMatcher.best(expected, listOf(remix)))
    }

    @Test
    fun handlesFeaturedArtists() {
        val expected = SpotifyTrack(
            title = "Song",
            artists = listOf("Artist A", "Artist B"),
            durationMs = 180_000L
        )
        val match = candidate(
            title = "Song",
            artist = "Artist A feat. Artist B",
            duration = 180L
        )

        assertEquals(match, TrackMatcher.best(expected, listOf(match)))
    }

    @Test
    fun prefersCloserDuration() {
        val expected = spotify("Song", "Artist", durationMs = 180_000L)
        val close = candidate(id = "close", title = "Song", artist = "Artist", duration = 181L)
        val far = candidate(id = "far", title = "Song", artist = "Artist", duration = 260L)

        assertTrue(
            TrackMatcher.score(expected, close) > TrackMatcher.score(expected, far)
        )
        assertEquals(close, TrackMatcher.best(expected, listOf(far, close)))
    }

    @Test
    fun doesNotDuplicateDeezerVersionField() {
        val candidate = candidate(
            title = "Stop The Wedding! (Original Piano Demo)",
            artist = "Ashe"
        )
        assertEquals("Stop The Wedding! (Original Piano Demo)", candidate.fullTitle)

        val versioned = candidate(
            title = "Song",
            version = "Remix",
            artist = "Artist"
        )
        assertEquals("Song Remix", versioned.fullTitle)
    }
}
