package dev.lelonio.square.data.local

import android.content.ContentResolver
import android.net.Uri

/** The Android entry: content uris, read through the resolver; the parsing is shared. */
class EmbeddedLyricsExtractor(
    private val contentResolver: ContentResolver,
) {
    private val parser = EmbeddedLyricsParser { contentResolver.openInputStream(Uri.parse(it)) }

    fun extract(
        contentUri: Uri,
        displayName: String? = null,
        mimeType: String? = null,
        filePath: String? = null,
    ): String? = parser.extract(contentUri.toString(), displayName, mimeType, filePath)
}
