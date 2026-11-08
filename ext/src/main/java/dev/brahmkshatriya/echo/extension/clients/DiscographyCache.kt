package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.extension.Utils.asObject
import dev.brahmkshatriya.echo.extension.Utils.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * Persists the release list and the raw track data of every release per artist.
 * Extensions have no storage API, so this lives in the app's temp directory
 * (the cache directory on Android). The OS may evict it; it is rebuilt on the
 * next open.
 */
internal class DiscographyCache(
    private val root: File = File(System.getProperty("java.io.tmpdir", "."), CACHE_DIR)
) {

    data class Entry(val lastAccess: Long, val releases: List<Release>)

    fun read(artistId: String): Entry? = read(metaFile(artistId))

    fun write(artistId: String, lastAccess: Long, releases: List<Release>) {
        val json = buildJsonObject {
            put("lastAccess", lastAccess)
            putJsonArray("releases") {
                releases.forEach { release ->
                    add(buildJsonObject {
                        put("id", release.id)
                        put("title", release.title)
                        put("releaseDate", release.releaseDate)
                        put("md5", release.md5)
                        put("recordType", release.recordType)
                        put("index", release.index)
                    })
                }
            }
        }
        runCatching { writeAtomic(metaFile(artistId), json.toString()) }
    }

    fun readTracks(artistId: String, releaseId: String): List<JsonElement>? = runCatching {
        Json.parseToJsonElement(trackFile(artistId, releaseId).readText()).jsonArray.toList()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun writeTracks(artistId: String, releaseId: String, data: List<JsonElement>) {
        if (data.isEmpty()) return
        runCatching {
            writeAtomic(trackFile(artistId, releaseId), JsonArray(data).toString())
        }
    }

    fun delete(artistId: String) {
        artistDir(artistId).deleteRecursively()
    }

    /** Removes artists untouched for [ttlMillis]. */
    fun pruneExpired(now: Long, ttlMillis: Long) {
        root.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val entry = read(File(dir, META_FILE))
            if (entry == null || now - entry.lastAccess > ttlMillis) dir.deleteRecursively()
        }
    }

    private fun artistDir(artistId: String) =
        File(root, artistId.replace(UNSAFE_ID, "_"))

    private fun metaFile(artistId: String) = File(artistDir(artistId), META_FILE)

    private fun trackFile(artistId: String, releaseId: String) =
        File(artistDir(artistId), releaseId.replace(UNSAFE_ID, "_") + ".json")

    private fun read(file: File): Entry? = runCatching {
        val obj = Json.parseToJsonElement(file.readText()).asObject()
        val releases = obj["releases"]?.jsonArray?.mapNotNull { element ->
            val release = element.asObject()
            val id = release.str("id") ?: return@mapNotNull null
            Release(
                id = id,
                title = release.str("title").orEmpty(),
                releaseDate = release.str("releaseDate"),
                md5 = release.str("md5"),
                recordType = release.str("recordType"),
                index = release.str("index")?.toIntOrNull() ?: 0
            )
        }.orEmpty()
        Entry(
            lastAccess = obj.str("lastAccess")?.toLongOrNull() ?: 0L,
            releases = releases
        )
    }.getOrNull()

    private fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "Could not write $file" }
        }
    }

    private companion object {
        const val CACHE_DIR = "echo-deezer-discography"
        const val META_FILE = "meta.json"
        val UNSAFE_ID = Regex("[^A-Za-z0-9_-]")
    }
}

internal data class Release(
    val id: String,
    val title: String,
    val releaseDate: String?,
    val md5: String?,
    val recordType: String?,
    val index: Int
)
