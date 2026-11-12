package dev.brahmkshatriya.echo.extension.api

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class SpotifyTrack(
    val title: String,
    val artists: List<String>,
    val durationMs: Long? = null,
    val album: String? = null,
) {
    val artistLine: String get() = artists.joinToString(", ")

    val displayName: String
        get() = if (artistLine.isBlank()) title else "$title \u2014 $artistLine"
}

data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val description: String?,
    val owner: String?,
    val coverUrl: String?,
    val tracks: List<SpotifyTrack>,
    val totalTracks: Int,
)

object SpotifyUrls {

    private val rawIdRegex = Regex("^[A-Za-z0-9]{16,32}$")
    private val urlRegex = Regex(
        "https?://open\\.spotify\\.com/(?:[^/\\s]+/)*playlist/([A-Za-z0-9]{16,32})(?:[/?#]|$)",
        RegexOption.IGNORE_CASE
    )
    private val uriRegex = Regex(
        "spotify:playlist:([A-Za-z0-9]{16,32})(?:[/?#]|$)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Extracts the playlist id from a Spotify URL, URI or a raw id.
     * Returns null when the input does not look like a playlist reference.
     */
    fun playlistId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (rawIdRegex.matches(trimmed)) return trimmed
        return urlRegex.find(trimmed)?.groupValues?.get(1)
            ?: uriRegex.find(trimmed)?.groupValues?.get(1)
    }
}

/**
 * Minimal client for reading public Spotify playlists without credentials.
 *
 * Spotify's official Web API is rate limited for anonymous tokens, so this
 * uses the same persisted GraphQL query that the Spotify web player uses. The
 * anonymous token is extracted from the public embed page. When the GraphQL
 * endpoint is unavailable the embed page's `__NEXT_DATA__` is used as a
 * fallback (limited to the first 50 tracks).
 */
class SpotifyClient(
    private val client: OkHttpClient = defaultHttpClient(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
) {

    suspend fun loadPlaylist(
        linkOrId: String,
        onProgress: suspend (loaded: Int, total: Int) -> Unit = { _, _ -> },
    ): SpotifyPlaylist {
        val id = SpotifyUrls.playlistId(linkOrId)
            ?: throw IllegalArgumentException("Please paste a valid public Spotify playlist link.")

        var embed = fetchEmbed(id)
        val firstAttempt = embed.token?.let { token ->
            runCatching { fetchGraphql(id, token, onProgress) }.getOrNull()
        }
        if (firstAttempt != null && firstAttempt.tracks.isNotEmpty()) return firstAttempt

        // The anonymous token can expire between requests. Fetch a fresh embed
        // page once before falling back to the (track limited) embed data.
        embed = fetchEmbed(id)
        val secondAttempt = embed.token?.let { token ->
            runCatching { fetchGraphql(id, token, onProgress) }.getOrNull()
        }
        if (secondAttempt != null && secondAttempt.tracks.isNotEmpty()) return secondAttempt

        val fallback = embed.fallback
        if (fallback != null && fallback.tracks.isNotEmpty()) return fallback

        if (firstAttempt?.totalTracks == 0) return firstAttempt
        if (secondAttempt?.totalTracks == 0) return secondAttempt

        throw Exception(
            "Could not read the Spotify playlist. Make sure the playlist is public and try again."
        )
    }

    private suspend fun fetchEmbed(id: String): EmbedPage {
        val request = Request.Builder()
            .url("https://open.spotify.com/embed/playlist/$id")
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val html = client.newCall(request).await().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw Exception("Spotify returned HTTP ${response.code}.")
            }
            body
        }

        val expiresAt = expirationRegex.find(html)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val token = tokenRegex.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?.takeIf { expiresAt == 0L || expiresAt > System.currentTimeMillis() }
        val fallback = runCatching { parseEmbedPlaylist(id, html) }.getOrNull()
        return EmbedPage(token, fallback)
    }

    private fun parseEmbedPlaylist(id: String, html: String): SpotifyPlaylist? {
        val nextData = nextDataRegex.find(html)?.groupValues?.get(1) ?: return null
        val root = json.parseToJsonElement(nextData) as? JsonObject ?: return null
        val entity = root.pathObject("props", "pageProps", "state", "data", "entity")
            ?: return null

        val name = entity.text("name") ?: return null
        val rows = entity["trackList"] as? JsonArray ?: return null
        val tracks = rows.mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val type = obj.text("entityType")
            if (type != null && type != "track") return@mapNotNull null
            val title = obj.text("title") ?: return@mapNotNull null
            val artists = obj.text("subtitle")
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            SpotifyTrack(
                title = title,
                artists = artists,
                durationMs = obj.long("duration")
            )
        }

        val cover = (entity["coverArt"] as? JsonObject)
            ?.get("sources")
            ?.let { it as? JsonArray }
            ?.firstSourceUrl()

        return SpotifyPlaylist(
            id = id,
            name = name,
            description = null,
            owner = entity.text("subtitle"),
            coverUrl = cover,
            tracks = tracks,
            totalTracks = tracks.size
        )
    }

    private suspend fun fetchGraphql(
        id: String,
        token: String,
        onProgress: suspend (loaded: Int, total: Int) -> Unit,
    ): SpotifyPlaylist {
        val tracks = mutableListOf<SpotifyTrack>()
        var name = ""
        var description: String? = null
        var owner: String? = null
        var coverUrl: String? = null
        var totalTracks = 0
        var offset = 0

        while (offset < MAX_TRACKS) {
            val response = executeGraphql(token, id, offset, PAGE_SIZE)
            val playlist = (response["data"] as? JsonObject)
                ?.get("playlistV2") as? JsonObject
                ?: throw Exception("Spotify did not return the playlist.")

            val content = playlist["content"] as? JsonObject
            if (offset == 0) {
                name = playlist.text("name").orEmpty()
                description = playlist.text("description")
                owner = (playlist["ownerV2"] as? JsonObject)
                    ?.get("data")?.let { it as? JsonObject }
                    ?.text("name")
                coverUrl = playlist.coverUrl()
                totalTracks = content?.int("totalCount") ?: 0
            }

            val items = content?.get("items") as? JsonArray ?: break
            if (items.isEmpty()) break

            items.forEach { item ->
                val wrapper = (item as? JsonObject)?.get("itemV2") as? JsonObject
                val data = wrapper?.get("data") as? JsonObject ?: return@forEach
                if (data.text("__typename") != "Track") return@forEach

                val title = data.text("name") ?: return@forEach
                val artists = (data["artists"] as? JsonObject)
                    ?.get("items")?.let { it as? JsonArray }
                    ?.mapNotNull { artist ->
                        ((artist as? JsonObject)?.get("profile") as? JsonObject)
                            ?.text("name")
                    }
                    .orEmpty()
                val duration = (data["trackDuration"] as? JsonObject)
                    ?.long("totalMilliseconds")
                val album = (data["albumOfTrack"] as? JsonObject)?.text("name")

                tracks += SpotifyTrack(
                    title = title,
                    artists = artists,
                    durationMs = duration,
                    album = album
                )
            }

            offset += items.size
            onProgress(tracks.size, if (totalTracks > 0) totalTracks else tracks.size)
            if (items.size < PAGE_SIZE) break
            if (totalTracks > 0 && offset >= totalTracks) break
        }

        return SpotifyPlaylist(
            id = id,
            name = name.ifBlank { "Spotify Playlist" },
            description = description,
            owner = owner,
            coverUrl = coverUrl,
            tracks = tracks,
            totalTracks = if (totalTracks > 0) totalTracks else tracks.size
        )
    }

    private suspend fun executeGraphql(
        token: String,
        id: String,
        offset: Int,
        limit: Int,
    ): JsonObject {
        val payload = buildJsonObject {
            putJsonObject("variables") {
                put("uri", "spotify:playlist:$id")
                put("offset", offset)
                put("limit", limit)
                put("enableWatchFeedEntrypoint", false)
            }
            put("operationName", "fetchPlaylist")
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", PLAYLIST_QUERY_HASH)
                }
            }
        }

        val request = Request.Builder()
            .url(GRAPHQL_URL)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Origin", "https://open.spotify.com")
            .header("Referer", "https://open.spotify.com/")
            .header("User-Agent", USER_AGENT)
            .build()

        val body = client.newCall(request).await().use { response ->
            val value = response.body.string()
            if (response.code == 401 || response.code == 403) {
                throw SpotifyAuthorizationException()
            }
            if (!response.isSuccessful) {
                throw Exception("Spotify returned HTTP ${response.code}.")
            }
            value
        }

        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw Exception("Spotify returned an unexpected response.")
        val errors = root["errors"] as? JsonArray
        if (errors != null && errors.isNotEmpty()) {
            throw Exception("Spotify returned an error: ${errors.first().toString().take(200)}")
        }
        return root
    }

    private class EmbedPage(
        val token: String?,
        val fallback: SpotifyPlaylist?,
    )

    private class SpotifyAuthorizationException : Exception("Spotify token expired.")

    private companion object {
        private const val GRAPHQL_URL = "https://api-partner.spotify.com/pathfinder/v2/query"
        private const val PLAYLIST_QUERY_HASH =
            "bb67e0af06e8d6f52b531f97468ee4acd44cd0f82b988e15c2ea47b1148efc77"
        private const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val PAGE_SIZE = 100
        private const val MAX_TRACKS = 10_000

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private val nextDataRegex = Regex(
            """<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL
        )
        private val tokenRegex = Regex("\"accessToken\"\\s*:\\s*\"([^\"]+)\"")
        private val expirationRegex =
            Regex("\"accessTokenExpirationTimestampMs\"\\s*:\\s*(\\d+)")

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}

private fun JsonObject.text(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return (element as? JsonPrimitive)?.content?.takeUnless { it == "null" }
}

private fun JsonObject.long(key: String): Long? = text(key)?.toLongOrNull()

private fun JsonObject.int(key: String): Int? = text(key)?.toIntOrNull()

private fun JsonObject.pathObject(vararg keys: String): JsonObject? {
    var current: JsonObject = this
    for (key in keys) {
        current = current[key] as? JsonObject ?: return null
    }
    return current
}

private fun JsonObject.coverUrl(): String? {
    val items = (this["images"] as? JsonObject)
        ?.get("items") as? JsonArray ?: return null
    return items.firstSourceUrl()
}

private fun JsonArray.firstSourceUrl(): String? {
    for (item in this) {
        val sources = (item as? JsonObject)?.get("sources") as? JsonArray ?: continue
        for (source in sources) {
            val url = (source as? JsonObject)?.text("url")
            if (!url.isNullOrBlank()) return url
        }
    }
    return null
}

private fun JsonElement.pathObject(vararg keys: String): JsonObject? =
    (this as? JsonObject)?.pathObject(*keys)
