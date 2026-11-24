package dev.lelonio.square.backend.subsonic

import dev.lelonio.square.data.CatalogTrack

/** What the catalogue asks of a platform's downloads: which songs are saved, and where. */
interface OfflineTracks {
    fun allCatalogTracks(): List<CatalogTrack>
    fun localPath(trackUri: String): String?
}
