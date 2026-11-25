package dev.lelonio.square.desktop

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DropTargetHandlerTest {

    @Test
    fun classifiesAudioFilesFoldersAndNonAudioFiles() {
        val dir = Files.createTempDirectory("drop-classify-")
        try {
            val subFolder = Files.createDirectory(dir.resolve("Albums"))
            val flac = Files.createFile(dir.resolve("track.flac"))
            val wav = Files.createFile(dir.resolve("tone.wav"))
            val mp3 = Files.createFile(dir.resolve("song.mp3"))
            val txt = Files.createFile(dir.resolve("notes.txt"))
            val png = Files.createFile(dir.resolve("cover.png"))
            val missing = dir.resolve("does-not-exist.flac")

            val result = DropTargetHandler.classifyDroppedPaths(
                listOf(flac, wav, mp3, subFolder, txt, png, missing, flac),
            )

            assertEquals(listOf(flac, wav, mp3), result.audioFiles)
            assertEquals(listOf(subFolder), result.folders)
            assertEquals(listOf(txt, png, missing), result.ignored)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun parsesUriListAndDeterminesDropZones() {
        val dir = Files.createTempDirectory("drop-uri-")
        try {
            val f = Files.createFile(dir.resolve("My Song.flac"))
            val uriList = "# comment\n${f.toUri()}\nhttps://example.com/ignored\n"
            val parsed = DropTargetHandler.parseUriList(uriList)
            assertEquals(listOf(f.toAbsolutePath().normalize()), parsed)

            val queueRect = Rect(900f, 50f, 1270f, 750f)
            val playlistRect = Rect(240f, 50f, 890f, 750f)

            assertEquals(
                DropZone.QUEUE_INSPECTOR,
                DropTargetHandler.determineDropZone(
                    xPx = 1000f,
                    yPx = 200f,
                    queueBounds = queueRect,
                    playlistBounds = playlistRect,
                    inspectorOpen = true,
                    inspectorTab = InspectorTab.QUEUE,
                    currentScreen = Screen.Playlist("local:playlist:1", "Mix"),
                ),
            )
            assertEquals(
                DropZone.LOCAL_PLAYLIST,
                DropTargetHandler.determineDropZone(
                    xPx = 400f,
                    yPx = 200f,
                    queueBounds = queueRect,
                    playlistBounds = playlistRect,
                    inspectorOpen = true,
                    inspectorTab = InspectorTab.QUEUE,
                    currentScreen = Screen.Playlist("local:playlist:1", "Mix"),
                ),
            )
            assertEquals(
                DropZone.MAIN,
                DropTargetHandler.determineDropZone(
                    xPx = 400f,
                    yPx = 200f,
                    queueBounds = queueRect,
                    playlistBounds = playlistRect,
                    inspectorOpen = true,
                    inspectorTab = InspectorTab.QUEUE,
                    currentScreen = Screen.Playlist("remote-1", "Server Mix"),
                ),
            )
            assertTrue(
                DropTargetHandler.showInFolderCommand(f, "Windows 11")
                    .let { it[0] == "explorer.exe" && it[1].startsWith("/select,") },
            )
            assertEquals(
                listOf("xdg-open", dir.toAbsolutePath().normalize().toString()),
                DropTargetHandler.showInFolderCommand(f, "Linux"),
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
