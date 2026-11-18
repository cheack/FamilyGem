package app.familygem.geni.api

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class PhotoDownloaderTest {

    private val server = MockWebServer()

    @get:Rule
    val folder = TemporaryFolder()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun downloader(maxBytes: Long = 1_000) =
        PhotoDownloader(maxBytes = maxBytes, allowHttp = true)

    private fun image(bytes: ByteArray, type: String = "image/jpeg") = MockResponse.Builder()
        .code(200).addHeader("Content-Type", type)
        .body(okio.Buffer().write(bytes)).build()

    @Test
    fun savesImageBytes() = runTest {
        val bytes = ByteArray(100) { it.toByte() }
        server.enqueue(image(bytes))
        val target = folder.newFile("a.jpg")
        val type = downloader().download(server.url("/p.jpg").toString(), target)
        assertEquals("image/jpeg", type)
        assertArrayEquals(bytes, target.readBytes())
        assertFalse(folder.root.resolve("a.jpg.part").exists())
    }

    @Test
    fun sendsNoAuthorizationHeader() = runTest {
        server.enqueue(image(ByteArray(5)))
        downloader().download(server.url("/p.jpg").toString(), folder.root.resolve("b.jpg"))
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun rejectsHtmlContentType() = runTest {
        server.enqueue(image("<html/>".toByteArray(), "text/html"))
        val target = folder.root.resolve("c.jpg")
        val error = runCatching { downloader().download(server.url("/p").toString(), target) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertFalse(target.exists())
        assertFalse(folder.root.resolve("c.jpg.part").exists())
    }

    @Test
    fun stopsAtSizeCapAndLeavesNoFile() = runTest {
        server.enqueue(image(ByteArray(2_000)))
        val target = folder.root.resolve("d.jpg")
        val error = runCatching { downloader(1_000).download(server.url("/p").toString(), target) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertFalse(target.exists())
        assertFalse(folder.root.resolve("d.jpg.part").exists())
    }

    @Test
    fun failedDownloadKeepsExistingTarget() = runTest {
        val target = folder.newFile("e.jpg").also { it.writeText("old") }
        server.enqueue(MockResponse.Builder().code(404).body("nope").build())
        val error = runCatching { downloader().download(server.url("/p").toString(), target) }.exceptionOrNull()
        assertTrue(error is GeniApiException)
        assertEquals(404, (error as GeniApiException).code)
        assertEquals("old", target.readText())
        assertFalse(folder.root.resolve("e.jpg.part").exists())
    }

    @Test
    fun refusesRedirects() = runTest {
        server.enqueue(
            MockResponse.Builder().code(302).headers(Headers.headersOf("Location", server.url("/other").toString())).build()
        )
        server.enqueue(image(ByteArray(5)))
        val target = folder.root.resolve("f.jpg")
        val error = runCatching { downloader().download(server.url("/p").toString(), target) }.exceptionOrNull()
        assertTrue(error is GeniApiException)
        assertEquals(1, server.requestCount)
        assertFalse(target.exists())
    }

    @Test
    fun refusesHttpByDefault() = runTest {
        val error = runCatching {
            PhotoDownloader().download("http://example.com/p.jpg", folder.root.resolve("g.jpg"))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
