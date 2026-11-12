package dev.brahmkshatriya.echo.extension.clients

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistResponseTest {

    private fun errorOf(json: String): String? =
        Json.parseToJsonElement(json).jsonObject.errorMessage()

    @Test
    fun emptyErrorFieldMeansSuccess() {
        assertNull(errorOf("""{"error":[],"results":"123"}"""))
        assertNull(errorOf("""{"error":{}}"""))
        assertNull(errorOf("""{"error":""}"""))
        assertNull(errorOf("""{"results":"123"}"""))
    }

    @Test
    fun reportsRealErrors() {
        assertEquals("nope", errorOf("""{"error":"nope"}"""))
        assertEquals(
            "Invalid CSRF token",
            errorOf("""{"error":{"VALID_TOKEN_REQUIRED":"Invalid CSRF token"}}""")
        )
        assertEquals(
            "this song already exists in this playlist",
            errorOf("""{"error":{"ERROR_DATA_EXISTS":"this song already exists in this playlist"}}""")
        )
        assertNotNull(errorOf("""{"error":[{"code":4}]}"""))
    }
}
