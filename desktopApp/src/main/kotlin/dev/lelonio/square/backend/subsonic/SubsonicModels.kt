package dev.lelonio.square.backend.subsonic

import kotlinx.serialization.Serializable

@Serializable
data class SubsonicResponseWrapper<T>(
    val `subsonic-response`: SubsonicResponse<T>,
)

@Serializable
data class SubsonicResponse<T>(
    val status: String,
    val version: String? = null,
    val type: String? = null,
    val serverVersion: String? = null,
    val openSubsonic: Boolean? = null,
    val error: SubsonicError? = null,
    val playlists: SubsonicPlaylistsContainer? = null,
    val playlist: SubsonicPlaylistDetail? = null,
    val albumList2: SubsonicAlbumList2Container? = null,
    val album: SubsonicAlbumDetail? = null,
    val artists: SubsonicArtistsContainer? = null,
    val artist: SubsonicArtistDetail? = null,
    val searchResult3: SubsonicSearchResult3? = null,
    val lyricsList: SubsonicLyricsListContainer? = null,
    val randomSongs: SubsonicRandomSongsContainer? = null,
)

@Serializable
data class SubsonicRandomSongsContainer(
    val song: List<SubsonicChildSong> = emptyList(),
)

@Serializable
data class SubsonicError(
    val code: Int,
    val message: String,
)

@Serializable
data class SubsonicPlaylistsContainer(
    val playlist: List<SubsonicPlaylistEntry> = emptyList(),
)

@Serializable
data class SubsonicPlaylistEntry(
    val id: String,
    val name: String,
    val comment: String? = null,
    val owner: String? = null,
    val public: Boolean = false,
    val songCount: Int = 0,
    val duration: Long = 0L,
    val coverArt: String? = null,
)

@Serializable
data class SubsonicPlaylistDetail(
    val id: String,
    val name: String,
    val entry: List<SubsonicChildSong> = emptyList(),
)

@Serializable
data class SubsonicAlbumList2Container(
    val album: List<SubsonicAlbumEntry> = emptyList(),
)

@Serializable
data class SubsonicAlbumEntry(
    val id: String,
    val name: String = "",
    val title: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Long = 0L,
    val year: Int? = null,
    val genre: String? = null,
)

@Serializable
data class SubsonicAlbumDetail(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Long = 0L,
    val year: Int? = null,
    val song: List<SubsonicChildSong> = emptyList(),
)

@Serializable
data class SubsonicArtistsContainer(
    val index: List<SubsonicArtistIndex> = emptyList(),
)

@Serializable
data class SubsonicArtistIndex(
    val name: String,
    val artist: List<SubsonicArtistItem> = emptyList(),
)

@Serializable
data class SubsonicArtistItem(
    val id: String,
    val name: String,
    val coverArt: String? = null,
    val albumCount: Int = 0,
)

@Serializable
data class SubsonicArtistDetail(
    val id: String,
    val name: String,
    val coverArt: String? = null,
    val albumCount: Int = 0,
    val album: List<SubsonicAlbumEntry> = emptyList(),
)

@Serializable
data class SubsonicSearchResult3(
    val artist: List<SubsonicArtistItem> = emptyList(),
    val album: List<SubsonicAlbumEntry> = emptyList(),
    val song: List<SubsonicChildSong> = emptyList(),
)

@Serializable
data class SubsonicChildSong(
    val id: String,
    val title: String,
    val album: String? = null,
    val albumId: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val track: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverArt: String? = null,
    val size: Long = 0L,
    val contentType: String? = null,
    val suffix: String? = null,
    val duration: Long = 0L,
    val bitRate: Int? = null,
    val samplingRate: Int? = null,
    val bitDepth: Int? = null,
    val path: String? = null,
    val discNumber: Int? = null,
)

@Serializable
data class SubsonicLyricsListContainer(
    val structuredLyrics: List<SubsonicStructuredLyrics> = emptyList(),
)

@Serializable
data class SubsonicStructuredLyrics(
    val lang: String? = null,
    val synced: Boolean = false,
    val line: List<SubsonicLyricsLine> = emptyList(),
)

@Serializable
data class SubsonicLyricsLine(
    val start: Long = 0L,
    val value: String = "",
)

data class SubsonicServerInfo(
    val status: String,
    val type: String,
    val serverVersion: String,
    val openSubsonic: Boolean,
)
