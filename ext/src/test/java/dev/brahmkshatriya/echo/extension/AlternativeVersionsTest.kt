package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.extension.api.SpotifyUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlternativeVersionsTest {

    @Test
    fun detectsRemixes() {
        assertTrue(AlternativeVersions.isAlternative("Blinding Lights (Remix)"))
        assertTrue(AlternativeVersions.isAlternative("Blinding Lights - Major Lazer Remix"))
        assertTrue(AlternativeVersions.isAlternative("Blinding Lights Remix"))
        assertTrue(AlternativeVersions.isAlternative("Song [RMX]"))
    }

    @Test
    fun detectsSlowedAndSpedUpVersions() {
        assertTrue(AlternativeVersions.isAlternative("Song - Slowed + Reverb"))
        assertTrue(AlternativeVersions.isAlternative("Song (Sped Up Version)"))
        assertTrue(AlternativeVersions.isAlternative("Song [Nightcore]"))
        assertTrue(AlternativeVersions.isAlternative("Song (8D Audio)"))
        assertTrue(AlternativeVersions.isAlternative("Song (128 BPM)"))
    }

    @Test
    fun detectsLiveCoverDemoAndInstrumentalVersions() {
        assertTrue(AlternativeVersions.isAlternative("Song - Live at Wembley"))
        assertTrue(AlternativeVersions.isAlternative("Song (Acoustic)"))
        assertTrue(AlternativeVersions.isAlternative("Song (Demo)"))
        assertTrue(AlternativeVersions.isAlternative("Song (Cover)"))
        assertTrue(AlternativeVersions.isAlternative("Song (Instrumental)"))
        assertTrue(AlternativeVersions.isAlternative("Song (Karaoke Version)"))
        assertTrue(AlternativeVersions.isAlternative("Song Live Version"))
        assertTrue(AlternativeVersions.isAlternative("Song Demo Version"))
        assertTrue(AlternativeVersions.isAlternative("Song Piano Version"))
        assertTrue(AlternativeVersions.isAlternative("Song Lullaby Version"))
        assertTrue(AlternativeVersions.isAlternative("Song (Lo-Fi)"))
    }

    @Test
    fun ignoresNormalSongTitles() {
        assertFalse(AlternativeVersions.isAlternative("Live and Let Die"))
        assertFalse(AlternativeVersions.isAlternative("Cover Me"))
        assertFalse(AlternativeVersions.isAlternative("Speed of Sound"))
        assertFalse(AlternativeVersions.isAlternative("Blinding Lights"))
        assertFalse(AlternativeVersions.isAlternative("The Remedy"))
        assertFalse(AlternativeVersions.isAlternative("Remedy"))
        assertFalse(AlternativeVersions.isAlternative("Tik Tok"))
        assertFalse(AlternativeVersions.isAlternative("Karaoke"))
        assertFalse(AlternativeVersions.isAlternative("Instrumental"))
        assertFalse(AlternativeVersions.isAlternative("Acapella"))
    }

    @Test
    fun detectsDeezerAppendedVersionField() {
        // Deezer's parser appends the separate version field to the title.
        assertTrue(AlternativeVersions.isAlternative("Song Live"))
        assertTrue(AlternativeVersions.isAlternative("Song Radio Edit"))
        assertTrue(AlternativeVersions.isAlternative("Song Extended"))
    }

    @Test
    fun supportsCustomKeywords() {
        assertTrue(
            AlternativeVersions.isAlternative("Song (Orchestral)", listOf("orchestral"))
        )
        assertFalse(
            AlternativeVersions.isAlternative("Blinding Lights", listOf("remix"))
        )
    }

    @Test
    fun skipsOnlySelectedKinds() {
        assertTrue(
            AlternativeVersions.isAlternative("Song (Remix)", kinds = setOf("remix", "live"))
        )
        assertFalse(
            AlternativeVersions.isAlternative("Song (Remix)", kinds = setOf("live"))
        )
        assertFalse(AlternativeVersions.isAlternative("Song (Remix)", kinds = emptySet()))

        // Custom keywords still apply when their kind is not selected.
        assertTrue(
            AlternativeVersions.isAlternative(
                "Song (Orchestral)",
                customKeywords = listOf("orchestral"),
                kinds = emptySet()
            )
        )
    }

    @Test
    fun everyDetectedKindHasALabel() {
        val titles = listOf(
            "Song (Remix)", "Song (Sped Up)", "Song - Live", "Song (Cover)",
            "Song (Instrumental)", "Song Radio Edit", "Song (Demo)", "Song (Acoustic)",
            "Song (8 Bit)", "Song (From the Movie)"
        )
        titles.forEach { title ->
            val kind = AlternativeVersions.kind(title)
            assertTrue("$title is detected", kind != null)
            assertTrue("$kind has a label", AlternativeVersions.kindLabels.containsKey(kind))
        }
    }

    @Test
    fun buildsBaseTitles() {
        assertEquals("song", AlternativeVersions.baseTitle("Song (Remix)"))
        assertEquals("song", AlternativeVersions.baseTitle("Song - Live"))
        assertEquals("blinding lights", AlternativeVersions.baseTitle("Blinding Lights"))
        assertEquals(
            "stop the wedding",
            AlternativeVersions.baseTitle("Stop The Wedding! (Original Piano Demo)")
        )
    }

    @Test
    fun normalizesText() {
        assertEquals("dont stop me now", AlternativeVersions.normalize("Don't Stop Me Now"))
        assertEquals("bjork", AlternativeVersions.normalize("Bj\u00f6rk"))
        assertEquals("ac dc", AlternativeVersions.normalize("AC/DC"))
    }

    @Test
    fun reportsAlternativeKinds() {
        assertEquals("remix", AlternativeVersions.kind("Song (Remix)"))
        assertEquals("speed", AlternativeVersions.kind("Song - Sped Up"))
        assertEquals("live", AlternativeVersions.kind("Song (Live)"))
        assertEquals("cover", AlternativeVersions.kind("Song (Cover)"))
        assertEquals("edit", AlternativeVersions.kind("Song Radio Edit"))
        assertEquals("live", AlternativeVersions.kind("Song Live Version"))
        assertEquals("instrumental", AlternativeVersions.kind("Song Piano Version"))
        assertEquals(null, AlternativeVersions.kind("Live and Let Die"))
    }

    @Test
    fun comparesTitles() {
        assertEquals(
            1.0,
            AlternativeVersions.similarity("blinding lights", "blinding lights"),
            0.0001
        )
        assertTrue(
            AlternativeVersions.similarity("blinding lights", "blinding light") > 0.9
        )
        assertTrue(
            AlternativeVersions.similarity("blinding lights", "completely different") < 0.5
        )
    }
}

class SpotifyUrlsTest {

    @Test
    fun parsesPlaylistUrls() {
        val id = "37i9dQZF1DXcBWIGoYBM5M"
        assertEquals(
            id,
            SpotifyUrls.playlistId("https://open.spotify.com/playlist/$id?si=abcdef")
        )
        assertEquals(
            id,
            SpotifyUrls.playlistId("https://open.spotify.com/intl-de/playlist/$id")
        )
        assertEquals(
            id,
            SpotifyUrls.playlistId("https://open.spotify.com/embed/playlist/$id")
        )
        assertEquals(id, SpotifyUrls.playlistId("spotify:playlist:$id"))
        assertEquals(id, SpotifyUrls.playlistId("  $id  "))
    }

    @Test
    fun rejectsNonPlaylistInput() {
        assertEquals(null, SpotifyUrls.playlistId(""))
        assertEquals(null, SpotifyUrls.playlistId("hello world"))
        assertEquals(
            null,
            SpotifyUrls.playlistId("https://open.spotify.com/track/3zOYmiNOuyDL0ClxJcEJ0T")
        )
        assertEquals(
            null,
            SpotifyUrls.playlistId("https://example.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        )
    }
}
