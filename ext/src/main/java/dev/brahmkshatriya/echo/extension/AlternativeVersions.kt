package dev.brahmkshatriya.echo.extension

import java.text.Normalizer
import java.util.Locale

/**
 * Detects alternative versions of songs (remixes, slowed/sped-up versions, live
 * recordings, covers, ...) based on their title.
 *
 * Deezer usually stores the version marker either inside the title itself
 * (`Song (Remix)`), as a suffix after a dash (`Song - Live at Wembley`) or in
 * the separate version field which the parser appends to the title
 * (`Song Remix`). Strong markers are matched anywhere in the title, weaker
 * markers only in bracketed/dashed suffixes or at the end of the title, so
 * that normal songs like "Live and Let Die" or "Cover Me" are not skipped.
 */
object AlternativeVersions {

    /**
     * Human readable names for every kind of alternative version that can be
     * detected. Used by the settings to let the user pick what to skip.
     */
    val kindLabels: Map<String, String> = linkedMapOf(
        "remix" to "Remixes & bootlegs",
        "speed" to "Sped up / slowed down",
        "live" to "Live recordings",
        "cover" to "Covers",
        "instrumental" to "Instrumental / karaoke",
        "edit" to "Edits & extended mixes",
        "demo" to "Demos",
        "acoustic" to "Acoustic versions",
        "chip" to "8-bit / chiptune",
        "soundtrack" to "Soundtrack versions"
    )

    private val bracketRegex = Regex("[(\uFF08\\[{]([^)\uFF09\\]}]*)[)\uFF09\\]}]")
    private val dashRegex = Regex("\\s+[-\u2013\u2014]\\s*")
    private val whitespaceRegex = Regex("\\s+")
    private val nonWordRegex = Regex("[^\\p{L}\\p{N}]+")
    private val diacriticsRegex = Regex("\\p{M}+")
    private val bpmRegex = Regex("(?:^| )\\d{2,3} bpm(?: |$)")
    private val versionTrailers = listOf("version", "mix", "edit", "recording", "take", "audio")

    /**
     * Markers that identify an alternative version no matter where they occur,
     * grouped by the kind of alternative version they produce.
     */
    private val strongKinds = listOf(
        "remix" to listOf(
            "remix", "remixed", "remixes", "rmx",
            "bootleg", "mashup", "mash up", "chopped and screwed", "chopped screwed",
            "vip mix", "club mix", "dub mix", "extended mix"
        ),
        "edit" to listOf("radio edit", "single edit"),
        "speed" to listOf(
            "slowed", "slowed down", "slowed reverb", "nightcore", "daycore",
            "sped up", "speed up", "spedup", "speedup"
        ),
        "instrumental" to listOf(
            "vocals only"
        ),
        "chip" to listOf("8 bit", "8bit", "chiptune"),
        "cover" to listOf(
            "in the style of", "made famous by", "originally performed by"
        )
    )

    /**
     * Markers that are only considered when they are part of a bracketed or
     * dashed version suffix, or at the very end of the title (the way Deezer
     * appends its version field).
     */
    private val weakKinds = listOf(
        "live" to listOf("live", "session", "sessions", "rehearsal"),
        "cover" to listOf("cover", "lullaby", "lullaby version"),
        "demo" to listOf("demo"),
        "acoustic" to listOf("acoustic", "unplugged"),
        "edit" to listOf(
            "edit", "mix", "extended", "radio edit", "club mix", "dub mix",
            "dub version", "extended mix", "alternate version",
            "alternative version", "alternate take", "special version",
            "lofi", "lo-fi", "lo fi", "medley", "bass boosted"
        ),
        "instrumental" to listOf(
            "instrumental", "a cappella", "acapella", "karaoke",
            "off vocal", "offvocal", "backing track", "vocals only",
            "orchestral", "symphonic", "piano", "guitar"
        ),
        "remix" to listOf("reverb", "reprise", "rework", "refix"),
        "speed" to listOf("8d audio", "8d", "tiktok", "tik tok", "nightcore", "daycore"),
        "soundtrack" to listOf(
            "soundtrack", "from the movie", "from the film", "from the soundtrack"
        )
    )

    /**
     * Lowercases the text, removes diacritics and punctuation and collapses
     * whitespace. Used for comparisons and matching.
     */
    fun normalize(text: String): String {
        val withoutMarks = Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replace(diacriticsRegex, "")
        val lowered = withoutMarks
            .lowercase(Locale.ROOT)
            .replace('\u2019', '\'')
            .replace('\u2018', '\'')
            .replace("\u201c", "\"")
            .replace("\u201d", "\"")
        val withoutApostrophes = lowered.replace("'", "")
        val withAnd = withoutApostrophes.replace("&", " and ")
        return nonWordRegex.replace(withAnd, " ")
            .trim()
            .replace(whitespaceRegex, " ")
    }

    /**
     * Strips bracketed parts and dash suffixes from a title and normalizes the
     * result. For example `Song (Remix)` and `Song - Live` both become `song`.
     * Useful to compare a track with its alternative versions.
     */
    fun baseTitle(title: String): String {
        var result = bracketRegex.replace(title, " ")
        val dash = dashRegex.find(result)
        if (dash != null) result = result.substring(0, dash.range.first)
        return normalize(result)
    }

    /**
     * Whether the title looks like an alternative version of a song.
     *
     * @param customKeywords additional keywords supplied by the user. These
     * are matched anywhere in the title.
     * @param kinds the kinds of alternative versions to skip. Defaults to all
     * of [kindLabels].
     */
    fun isAlternative(
        title: String,
        customKeywords: List<String> = emptyList(),
        kinds: Set<String> = kindLabels.keys,
    ): Boolean {
        val titleKind = kind(title)
        if (titleKind != null && titleKind in kinds) return true
        val normalized = normalize(title)
        if (normalized.isBlank()) return false
        return customKeywords.any { keyword ->
            val normalizedKeyword = normalize(keyword)
            normalizedKeyword.isNotBlank() && containsPhrase(normalized, normalizedKeyword)
        }
    }

    /**
     * Returns the kind of alternative version (for example `remix`, `speed`,
     * `live`, `cover`) or null when the title does not look like one.
     */
    fun kind(title: String): String? {
        if (title.isBlank()) return null
        val normalized = normalize(title)
        if (normalized.isBlank()) return null

        for ((kind, keywords) in strongKinds) {
            if (keywords.any { containsPhrase(normalized, it) }) return kind
        }
        if (bpmRegex.containsMatchIn(normalized)) return "speed"

        val suffixes = versionSuffixes(title)
        for (suffix in suffixes) {
            for ((kind, keywords) in weakKinds) {
                if (keywords.any {
                        containsPhrase(suffix, it) || endsWithVersionPhrase(suffix, it)
                    }
                ) return kind
            }
        }

        // Deezer appends its version field directly to the title ("Song Live").
        // Requiring more than one word avoids matching a song that is simply
        // called "Live" or "Cover".
        if (normalized.count { it == ' ' } >= 1) {
            for ((kind, keywords) in weakKinds) {
                if (keywords.any { endsWithVersionPhrase(normalized, it) }) return kind
            }
        }
        return null
    }

    /**
     * Returns the normalized bracketed and dashed suffixes of a title.
     */
    fun versionSuffixes(title: String): List<String> {
        val suffixes = mutableListOf<String>()
        bracketRegex.findAll(title).forEach { match ->
            normalize(match.groupValues[1]).takeIf { it.isNotBlank() }?.let(suffixes::add)
        }
        dashRegex.findAll(title).forEach { match ->
            normalize(title.substring(match.range.last + 1))
                .takeIf { it.isNotBlank() }?.let(suffixes::add)
        }
        return suffixes
    }

    /**
     * Similarity of two normalized strings between 0 and 1. Exact matches and
     * contained titles score high, otherwise a normalized Levenshtein
     * distance is used.
     */
    fun similarity(first: String, second: String): Double {
        if (first.isBlank() || second.isBlank()) return 0.0
        if (first == second) return 1.0
        val shorter = minOf(first.length, second.length)
        if (shorter >= 4 && (isContained(first, second) || isContained(second, first))) {
            return 0.92
        }
        val longer = maxOf(first.length, second.length)
        val distance = levenshtein(first, second)
        return (1.0 - distance.toDouble() / longer).coerceIn(0.0, 1.0)
    }

    private fun isContained(needle: String, haystack: String): Boolean =
        containsPhrase(haystack, needle)

    private fun containsPhrase(text: String, phrase: String): Boolean {
        val normalizedPhrase = normalize(phrase)
        if (normalizedPhrase.isBlank()) return false
        return " $text ".contains(" $normalizedPhrase ")
    }

    /**
     * Matches a weak marker at the end of the title, either directly
     * (`Song Live`) or followed by a generic version word (`Song Live
     * Version`). A title that consists only of the marker itself is not
     * treated as a version, so songs like "Tik Tok" keep playing.
     */
    private fun endsWithVersionPhrase(text: String, phrase: String): Boolean {
        val normalizedPhrase = normalize(phrase)
        if (normalizedPhrase.isBlank() || text == normalizedPhrase) return false
        if (text.endsWith(" $normalizedPhrase")) return true
        return versionTrailers.any { trailer ->
            text.endsWith(" $normalizedPhrase $trailer")
        }
    }

    private fun levenshtein(first: String, second: String): Int {
        if (first == second) return 0
        if (first.isEmpty()) return second.length
        if (second.isEmpty()) return first.length

        var previous = IntArray(second.length + 1) { it }
        var current = IntArray(second.length + 1)

        for (i in 1..first.length) {
            current[0] = i
            for (j in 1..second.length) {
                val cost = if (first[i - 1] == second[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[second.length]
    }
}
