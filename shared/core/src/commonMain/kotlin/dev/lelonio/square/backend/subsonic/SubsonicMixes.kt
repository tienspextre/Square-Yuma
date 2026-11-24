package dev.lelonio.square.backend.subsonic

/**
 * Listening by mood or genre on a Navidrome library.
 *
 * Subsonic knows genres only, so a mood is a set of genres. Tag spellings vary
 * by store and language ("Électronique", "Bandes originales de films"), so each
 * genre is folded onto one name first, and a list asks the server for every raw
 * spelling behind it.
 */
object SubsonicMixes {
    const val URI_PREFIX = "subsonic:mix:"

    /** The display name a raw tag is listed under. */
    private val ALIASES = mapOf(
        "alternatif et indé" to "Alternative & Indie",
        "électronique" to "Electronic",
        "electronica" to "Electronic",
        "bandes originales de films" to "Film Soundtracks",
        "film" to "Film Soundtracks",
        "soundtrack" to "Film Soundtracks",
        "classique" to "Classical",
        "hip hop" to "Hip-Hop",
        "rnb" to "R&B",
        "r&b/soul" to "R&B",
        "kpop" to "K-Pop",
        "jpop" to "J-Pop",
        "j-pop" to "J-Pop",
    )

    /** Mood to the canonical genres that make it up, in the order they are offered. */
    val MOODS: List<Pair<String, Set<String>>> = listOf(
        "Chill" to setOf("Jazz", "Soul", "R&B", "New Age", "Classical", "Folk", "Country", "Americana", "Acoustic", "Ambient", "Lo-Fi"),
        "Party" to setOf("Dance", "House", "Techno", "Electronic", "EDM", "Hip-Hop", "Rap", "K-Pop"),
        "Workout" to setOf("Rock", "Hip-Hop", "Rap", "Dance", "Electronic", "Techno", "Metal", "EDM", "Drum & Bass"),
        "Focus" to setOf("Classical", "Film Soundtracks", "New Age", "Ambient", "Instrumental", "Jazz"),
        "Feel Good" to setOf("Pop", "K-Pop", "J-Pop", "Dance", "Country", "Soul", "R&B"),
    )

    fun canonical(raw: String): String {
        val trimmed = raw.trim()
        return ALIASES[trimmed.lowercase()] ?: trimmed
    }

    data class Mix(val key: String, val title: String, val kind: String, val rawGenres: List<String>, val songCount: Int)

    /**
     * Every mood with music behind it, then every genre, biggest first. [genres]
     * is the server's own list; genres with fewer than [minSongs] songs are left
     * out of the genre lists (they still count toward a mood).
     */
    fun build(genres: List<SubsonicGenre>, minSongs: Int = 3): List<Mix> {
        val byName = genres.filter { it.value.isNotBlank() }.groupBy { canonical(it.value) }
        val genreMixes = byName.map { (name, raws) ->
            Mix("genre:$name", name, "GENRE", raws.map { it.value }, raws.sumOf { it.songCount })
        }.filter { it.songCount >= minSongs }.sortedByDescending { it.songCount }
        val moodMixes = MOODS.mapNotNull { (mood, members) ->
            val raws = byName.filterKeys { name -> members.any { it.equals(name, ignoreCase = true) } }.values.flatten()
            if (raws.isEmpty()) null
            else Mix("mood:$mood", mood, "MOOD", raws.map { it.value }, raws.sumOf { it.songCount })
        }
        return moodMixes + genreMixes
    }

    fun find(mixes: List<Mix>, key: String): Mix? = mixes.firstOrNull { it.key == key }
}
