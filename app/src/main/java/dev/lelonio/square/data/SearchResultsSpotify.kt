package dev.lelonio.square.data

/**
 * Maps a Web API search response.
 *
 * Tracks are asked for in greater number than the rest and shown first: finding
 * a song is what a search box in a music player is mostly for, and burying it
 * under artist and album rows makes the common case the slow one.
 */
fun SearchDto.toResults(
    /** What each kind is called, in the app's language; see MainViewModel. */
    artistLabel: String,
    albumLabel: String,
    playlistLabel: String,
): SearchResults = SearchResults(
    tracks = tracks?.items.orEmpty().map { it.toCatalogTrack() },
    artists = artists?.items.orEmpty().mapNotNull { artist ->
        SearchItem(
            uri = artist.uri ?: return@mapNotNull null,
            title = artist.name,
            subtitle = artistLabel,
            artworkUrl = artist.images.firstOrNull()?.url,
        )
    },
    albums = albums?.items.orEmpty().mapNotNull { album ->
        SearchItem(
            uri = album.uri ?: return@mapNotNull null,
            title = album.name,
            subtitle = albumLabel,
            artworkUrl = album.images.firstOrNull()?.url,
        )
    },
    // Spotify returns nulls in this array for playlists it will not serve, so
    // the element type is nullable and the nulls are dropped rather than
    // crashing the whole response.
    playlists = playlists?.items.orEmpty().filterNotNull().map { playlist ->
        SearchItem(
            uri = playlist.uri,
            title = playlist.name,
            subtitle = playlistLabel,
            artworkUrl = playlist.images.firstOrNull()?.url,
        )
    },
)
