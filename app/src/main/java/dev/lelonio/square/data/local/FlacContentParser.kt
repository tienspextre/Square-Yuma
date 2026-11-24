package dev.lelonio.square.data.local

import android.content.Context
import android.net.Uri

/** The Android entry for a content uri; the parsing is [FlacMetadataParser]'s. */
fun FlacMetadataParser.parse(context: Context, uri: Uri): FlacTrackInfo? = runCatching {
    val length = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    context.contentResolver.openInputStream(uri)?.use { parse(it, length) }
}.getOrNull()
