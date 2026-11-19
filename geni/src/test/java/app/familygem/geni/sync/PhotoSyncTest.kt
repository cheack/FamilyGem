package app.familygem.geni.sync

import app.familygem.geni.FakeTreeHost
import app.familygem.geni.api.GeniProfile
import app.familygem.geni.map.GeniMapper
import kotlinx.coroutines.test.runTest
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Media
import org.folg.gedcom.model.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class PhotoSyncTest {

    private val host = FakeTreeHost()
    private val store = InMemorySyncStore()
    private val gedcom = Gedcom()
    private val downloads = mutableListOf<String>()
    private var failNext = false
    private var bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

    private val sync = PhotoSync(store, host) { url, target ->
        downloads += url
        if (failNext) throw IOException("network")
        target.writeBytes(bytes)
        "image/jpeg"
    }

    private val treeId: Int

    init {
        gedcom.addPerson(Person().apply { id = "I1"; GeniMapper.setGeniId(this, "profile-1") })
        treeId = kotlinx.coroutines.runBlocking { host.createTree("t", gedcom) }
    }

    private fun profile(photoId: String?, query: String = "H.1") = GeniProfile(
        id = "profile-1",
        mugshotUrls = photoId?.let {
            mapOf(
                "large" to "https://photos.example/$it/large.jpg?hash=$query",
                "medium" to "https://photos.example/$it/medium.jpg?hash=$query",
                "url" to "https://api.example/$it"
            )
        }
    )

    private val person get() = gedcom.people.single()
    private fun dir() = host.mediaDir(treeId)

    @Test
    fun newPhotoIsDownloadedAndAttachedAsPrimary() = runTest {
        assertTrue(sync.sync(treeId, profile("photo-7"), "I1"))
        assertEquals(listOf("https://photos.example/photo-7/medium.jpg?hash=H.1"), downloads)
        val media = person.media.single()
        assertEquals("geni-photo-7.jpg", media.file)
        assertEquals("Y", media.primary)
        assertEquals("photo-7", GeniMapper.getTag(media, PhotoSync.PHOTO_TAG))
        assertTrue(File(dir(), "geni-photo-7.jpg").isFile)
        val link = store.photoLinks(treeId, "profile-1").single()
        assertEquals(PhotoLink(treeId, "profile-1", "photo-7", null, true, "https://photos.example/photo-7/medium.jpg", "geni-photo-7.jpg", PhotoState.DOWNLOADED), link)
    }

    @Test
    fun unchangedPhotoIsNotDownloadedAgainEvenWithNewSignedUrl() = runTest {
        sync.sync(treeId, profile("photo-7", "H.1"), "I1")
        assertFalse(sync.sync(treeId, profile("photo-7", "H.2"), "I1"))
        assertEquals(1, downloads.size)
        assertEquals(1, person.media.size)
    }

    @Test
    fun changedPhotoReplacesMediaAndFile() = runTest {
        sync.sync(treeId, profile("photo-7"), "I1")
        assertTrue(sync.sync(treeId, profile("photo-8"), "I1"))
        assertEquals(listOf("geni-photo-8.jpg"), person.media.map { it.file })
        assertFalse(File(dir(), "geni-photo-7.jpg").exists())
        assertTrue(File(dir(), "geni-photo-8.jpg").isFile)
        assertEquals(listOf("photo-8"), store.photoLinks(treeId).map { it.geniPhotoId })
    }

    @Test
    fun photoGoneOnGeniIsRemoved() = runTest {
        sync.sync(treeId, profile("photo-7"), "I1")
        assertTrue(sync.sync(treeId, profile(null), "I1"))
        assertTrue(person.media.isEmpty())
        assertFalse(File(dir(), "geni-photo-7.jpg").exists())
        assertTrue(store.photoLinks(treeId).isEmpty())
    }

    @Test
    fun noPhotoAndNoLinkChangesNothing() = runTest {
        person.addMedia(Media().apply { file = "mine.jpg" })
        assertFalse(sync.sync(treeId, profile(null), "I1"))
        assertEquals(listOf("mine.jpg"), person.media.map { it.file })
    }

    @Test
    fun failedDownloadKeepsOldPhoto() = runTest {
        sync.sync(treeId, profile("photo-7"), "I1")
        failNext = true
        val error = runCatching { sync.sync(treeId, profile("photo-8"), "I1") }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(listOf("geni-photo-7.jpg"), person.media.map { it.file })
        assertTrue(File(dir(), "geni-photo-7.jpg").isFile)
        assertEquals(listOf("photo-7"), store.photoLinks(treeId).map { it.geniPhotoId })
        assertEquals(listOf("geni-photo-7.jpg"), dir().list()!!.toList())
    }

    @Test
    fun nonImageIsRejected() = runTest {
        bytes = "<html>denied</html>".toByteArray()
        val error = runCatching { sync.sync(treeId, profile("photo-7"), "I1") }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(person.media.isEmpty())
        assertTrue(store.photoLinks(treeId).isEmpty())
        assertEquals(emptyList<String>(), dir().list()!!.toList())
    }

    @Test
    fun localMediaStaysAndGeniPhotoBecomesPrimary() = runTest {
        person.addMedia(Media().apply { file = "mine.jpg"; primary = "Y" })
        sync.sync(treeId, profile("photo-7"), "I1")
        assertEquals(listOf("mine.jpg", "geni-photo-7.jpg"), person.media.map { it.file })
        assertNull(person.media[0].primary)
        assertEquals("Y", person.media[1].primary)
    }

    @Test
    fun personWithOtherGeniIdIsIgnored() = runTest {
        GeniMapper.setGeniId(person, "profile-2")
        assertFalse(sync.sync(treeId, profile("photo-7"), "I1"))
        assertTrue(person.media.isEmpty())
        assertTrue(store.photoLinks(treeId).isEmpty())
    }

    @Test
    fun missingFileIsDownloadedAgain() = runTest {
        sync.sync(treeId, profile("photo-7"), "I1")
        File(dir(), "geni-photo-7.jpg").delete()
        assertTrue(sync.sync(treeId, profile("photo-7"), "I1"))
        assertEquals(2, downloads.size)
        assertEquals(1, person.media.size)
    }

    @Test
    fun sharedFileStaysWhileAnotherProfileUsesIt() = runTest {
        gedcom.addPerson(Person().apply { id = "I2"; GeniMapper.setGeniId(this, "profile-2") })
        sync.sync(treeId, profile("photo-7"), "I1")
        sync.sync(treeId, profile("photo-7").copy(id = "profile-2"), "I2")
        assertTrue(sync.sync(treeId, profile(null), "I1"))
        assertTrue(File(dir(), "geni-photo-7.jpg").isFile)
        assertTrue(sync.sync(treeId, profile(null).copy(id = "profile-2"), "I2"))
        assertFalse(File(dir(), "geni-photo-7.jpg").exists())
    }

    @Test
    fun detectsImageHeaders() {
        fun file(vararg b: Int) = File.createTempFile("img", null).apply { writeBytes(ByteArray(b.size) { b[it].toByte() }); deleteOnExit() }
        assertTrue(PhotoSync.looksLikeImage(file(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
        assertTrue(PhotoSync.looksLikeImage(file(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)))
        assertTrue(PhotoSync.looksLikeImage(file(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50)))
        assertFalse(PhotoSync.looksLikeImage(file(0x3C, 0x68, 0x74, 0x6D, 0x6C)))
        assertFalse(PhotoSync.looksLikeImage(file(1, 2)))
    }
}
