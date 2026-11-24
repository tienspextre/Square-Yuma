package dev.lelonio.square.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A track as shown in the UI.
 *
 * Deliberately flat and free of Web API shapes: it is produced by the native
 * catalogue, and nothing above this layer should care which transport supplied
 * it.
 */
@Serializable
data class CatalogTrack(
    val uri: String,
    val name: String,
    val artist: String,
    /** The first artist's own uri, when the source gives one; see catalog.rs. */
    val artistUri: String? = null,
    /**
     * Every artist credited, named and addressed.
     *
     * [artist] is these names run together for the places that show one line;
     * this is what makes each of them its own way into the app.
     */
    val artists: List<CatalogArtist> = emptyList(),
    val album: String = "",
    /**
     * The record's own address, where the source gives one.
     *
     * What makes the album under a title in the player a way to reach it rather
     * than a caption. Null through the access point, which names the record but
     * does not address it; the player falls back to a lookup there.
     */
    val albumUri: String? = null,
    /**
     * The year the record came out, or empty where the source does not say.
     *
     * Only the four digits: an artist page names a song by the record it is on
     * and when that record happened, and the month and day of a release are not
     * how anybody places an album.
     */
    val year: String = "",
    val durationMs: Long = 0,
    val explicit: Boolean = false,
    val smartRecommended: Boolean = false,
    val artworkUrl: String? = null,
    /**
     * When the track was added to the playlist it was read from, ISO-8601.
     *
     * Only the Web API knows this — the access point's context has no such
     * field — so it is null for anything resolved through the fallback path,
     * and sorting by it then leaves the list in playlist order.
     */
    val addedAt: String? = null,
    /**
     * The file on this phone that plays for this entry, where there is one.
     *
     * Set for the phone's own music, and for a local file a Spotify playlist
     * names once a file here has been matched to it (see
     * LocalLibrary.match). Null on a `spotify:local:` entry means this phone
     * has nothing to play for it.
     */
    val localFile: String? = null,
    /**
     * For a podcast episode: when it came out, ISO-8601. Null for music, whose
     * release is a year on its record rather than a day.
     */
    val releasedAt: String? = null,
    /** For an episode: how far into it the listener got, where the source says. */
    val resumeMs: Long = 0,
    /** For an episode: whether the listener heard it to the end. */
    val played: Boolean = false,
    /** For an episode: whether it comes with a picture as well as sound. */
    val hasVideo: Boolean = false,
    /** For an episode: what the show says it is about, as plain text. */
    val summary: String = "",
) {
    /**
     * A podcast episode rather than a song, on either source.
     *
     * What the player and the lists ask before offering what only a song has:
     * lyrics, a heart, a radio, credits.
     */
    val isEpisode: Boolean
        get() = uri.startsWith("spotify:episode:") || releasedAt != null
}

/**
 * A track's Canvas.
 *
 * @param isVideo false for the handful of canvases that are a still image; those
 *   are shown as a picture rather than handed to a video surface.
 */
data class CanvasClip(val url: String, val isVideo: Boolean)

/** A playlist owned by the logged-in account. */
@Serializable
data class CatalogPlaylist(
    val uri: String,
    val name: String,
    /** Null when the playlist has no custom cover; the UI draws a tile instead. */
    val artworkUrl: String? = null,
    /**
     * Who it is by, or what it is: the line a home shelf writes under the name.
     *
     * Null in the account's own library, where every row is the user's and the
     * line would say nothing.
     */
    val subtitle: String? = null,
    /**
     * An artist rather than a record.
     *
     * The only thing the tile does differently, and it does matter: a round
     * portrait among square covers is how a shelf says "these are people".
     */
    val isArtist: Boolean = false,
    /**
     * Whether it is the account's own, where the account's index says: what
     * decides if it can be edited. Null when nobody said.
     */
    val mine: Boolean? = null,
)

/** One credited artist: what to write, and where it leads. */
@Serializable
data class CatalogArtist(val name: String, val uri: String? = null)
