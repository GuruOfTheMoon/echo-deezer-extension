package dev.brahmkshatriya.echo.extension.clients

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class DiscographyCacheTest {

    @Test
    fun releasesAndTracksSurviveRestart() {
        val dir = Files.createTempDirectory("discography-cache").toFile()
        try {
            val release = Release("1", "Album", "2020-01-01", "md5", "album", 0)
            val tracks = listOf(JsonPrimitive("a"), JsonPrimitive("b"))
            DiscographyCache(dir).apply {
                write("artist", 1000L, listOf(release))
                writeTracks("artist", "1", tracks)
            }

            val reopened = DiscographyCache(dir)
            assertEquals(1000L, reopened.read("artist")!!.lastAccess)
            assertEquals(listOf(release), reopened.read("artist")!!.releases)
            assertEquals(tracks, reopened.readTracks("artist", "1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pruneRemovesOnlyInactiveArtists() {
        val day = 24L * 60 * 60 * 1000
        val now = 10 * day
        val dir = Files.createTempDirectory("discography-cache").toFile()
        try {
            DiscographyCache(dir).apply {
                write("old", now - 8 * day, emptyList())
                writeTracks("old", "1", listOf(JsonPrimitive("x")))
                write("active", now - 6 * day, emptyList())
            }

            val cache = DiscographyCache(dir)
            cache.pruneExpired(now, 7 * day)

            assertNull(cache.read("old"))
            assertNull(cache.readTracks("old", "1"))
            assertNotNull(cache.read("active"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
