package app.familygem.geni.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
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

    @Test
    fun cancellationStopsDownloadAndKeepsTarget() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "image/jpeg")
                .body(okio.Buffer().write(ByteArray(900)))
                .throttleBody(10, 100, TimeUnit.MILLISECONDS).build()
        )
        val target = folder.newFile("h.jpg").also { it.writeText("old") }
        val start = System.currentTimeMillis()
        val job = launch(Dispatchers.Default) { downloader().download(server.url("/p").toString(), target) }
        assertNotNull("download never reached the server", server.takeRequest(5, TimeUnit.SECONDS))
        Thread.sleep(300)
        job.cancelAndJoin()
        assertTrue(System.currentTimeMillis() - start < 5_000)
        assertEquals("old", target.readText())
        assertFalse(folder.root.resolve("h.jpg.part").exists())
    }

    private fun assertCancelsPromptly(
        response: MockResponse, name: String, awaitStalled: (File) -> Unit = { Thread.sleep(500) }
    ) = runBlocking {
        server.enqueue(response)
        val target = folder.newFile(name).also { it.writeText("old") }
        var failure: Throwable? = null
        val job = launch(Dispatchers.Default) {
            try {
                downloader().download(server.url("/p").toString(), target)
            } catch (e: Throwable) {
                failure = e
                throw e
            }
        }
        assertNotNull("download never reached the server", server.takeRequest(5, TimeUnit.SECONDS))
        awaitStalled(folder.root)
        val start = System.currentTimeMillis()
        job.cancelAndJoin()
        assertTrue("cancel took too long", System.currentTimeMillis() - start < 3_000)
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertEquals("old", target.readText())
        assertTrue(folder.root.listFiles { f -> f.name.endsWith(".part") }.orEmpty().isEmpty())
    }

    @Test
    fun cancellationInterruptsStalledBodyRead() = assertCancelsPromptly(
        MockResponse.Builder().code(200).addHeader("Content-Type", "image/jpeg")
            .body(okio.Buffer().write(ByteArray(900)))
            .throttleBody(1, 60, TimeUnit.SECONDS).build(),
        "i.jpg"
    ) { dir ->
        // Cancel only once the body read is in progress: first bytes already staged in a .part file
        val deadline = System.currentTimeMillis() + 5_000
        while (dir.listFiles { f -> f.name.endsWith(".part") && f.length() > 0 }.orEmpty().isEmpty()) {
            assertTrue("body never started", System.currentTimeMillis() < deadline)
            Thread.sleep(20)
        }
    }

    @Test
    fun cancellationInterruptsStalledResponse() = assertCancelsPromptly(
        MockResponse.Builder().onRequestStart(mockwebserver3.SocketEffect.Stall).build(),
        "j.jpg"
    )

    @Test
    fun emptyBodyKeepsExistingTarget() = runTest {
        val target = folder.newFile("k.jpg").also { it.writeText("old") }
        server.enqueue(image(ByteArray(0)))
        val error = runCatching { downloader().download(server.url("/p").toString(), target) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals("old", target.readText())
        assertTrue(folder.root.listFiles { f -> f.name.endsWith(".part") }.orEmpty().isEmpty())
    }

    @Test
    fun suppliedClientStillNeverFollowsRedirects() = runTest {
        server.enqueue(
            MockResponse.Builder().code(302).headers(Headers.headersOf("Location", server.url("/other").toString())).build()
        )
        server.enqueue(image(ByteArray(5)))
        val following = okhttp3.OkHttpClient.Builder().followRedirects(true).build()
        val target = folder.root.resolve("l.jpg")
        val error = runCatching {
            PhotoDownloader(following, allowHttp = true).download(server.url("/p").toString(), target)
        }.exceptionOrNull()
        assertTrue(error is GeniApiException)
        assertEquals(1, server.requestCount)
        assertFalse(target.exists())
    }

    @Test
    fun failedDownloadDoesNotDeleteAnotherDownloadsStagingFile() = runTest {
        val other = folder.root.resolve("m.jpg.other.part").also { it.writeText("in progress") }
        server.enqueue(MockResponse.Builder().code(404).body("nope").build())
        runCatching { downloader().download(server.url("/p").toString(), folder.root.resolve("m.jpg")) }
        assertTrue(other.exists())
    }
}
