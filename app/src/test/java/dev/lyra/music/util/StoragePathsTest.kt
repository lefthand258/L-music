package dev.lyra.music.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePathsTest {
    @Test fun primary_volume() {
        assertEquals("/storage/emulated/0/Music/Hi-Res", StoragePaths.documentIdToPath("primary:Music/Hi-Res"))
        assertEquals("/storage/emulated/0", StoragePaths.documentIdToPath("primary:"))
    }

    @Test fun sd_card_volume() {
        assertEquals("/storage/1A2B-3C4D/Music", StoragePaths.documentIdToPath("1A2B-3C4D:Music/"))
    }

    @Test fun rejects_non_volume_ids_and_traversal() {
        assertNull(StoragePaths.documentIdToPath("msf:1234"))
        assertNull(StoragePaths.documentIdToPath("raw"))
        assertNull(StoragePaths.documentIdToPath("primary:Music/../../data"))
    }

    @Test fun audio_extensions() {
        assertTrue(StoragePaths.isAudioFileName("a.FLAC"))
        assertTrue(StoragePaths.isAudioFileName("夜曲.ape"))
        assertTrue(StoragePaths.isAudioFileName("x.y.dsf"))
        assertFalse(StoragePaths.isAudioFileName("cover.jpg"))
        assertFalse(StoragePaths.isAudioFileName("noext"))
    }

    @Test fun library_ids_group_by_normalised_names() {
        val a = LibraryIds.albumId("七里香", "周杰伦", "/m/a")
        assertEquals(a, LibraryIds.albumId("  七里香 ", "周杰伦", "/m/b"))
        assertEquals(LibraryIds.albumId("Abbey  Road", "The Beatles", "/x"), LibraryIds.albumId("abbey road", "the beatles", "/y"))
        assertNotEquals(a, LibraryIds.albumId("七里香", "Someone Else", "/m/a"))
        assertNotEquals(a, LibraryIds.albumId("叶惠美", "周杰伦", "/m/a"))
        // No album title → grouped per folder.
        assertEquals(LibraryIds.albumId("", "x", "/m/a"), LibraryIds.albumId("", "y", "/m/a"))
        assertNotEquals(LibraryIds.albumId("", "x", "/m/a"), LibraryIds.albumId("", "x", "/m/b"))
        assertTrue(a > 0)
        assertEquals(LibraryIds.artistId("周杰伦"), LibraryIds.artistId(" 周杰伦"))
        assertNotEquals(LibraryIds.artistId("周杰伦"), LibraryIds.artistId("周杰"))
    }
}

class GenreSplitTest {
    @Test fun split_separators() {
        assertEquals(listOf("Pop", "Rock", "古风", "Jazz"), dev.lyra.music.data.scanner.GenreText.split("Pop; Rock / 古风、Jazz"))
        assertEquals(listOf("流行 Pop"), dev.lyra.music.data.scanner.GenreText.split(" 流行 Pop "))
        assertEquals(emptyList<String>(), dev.lyra.music.data.scanner.GenreText.split(null))
        assertEquals(listOf("A"), dev.lyra.music.data.scanner.GenreText.split("A;A,a "))
    }
}
