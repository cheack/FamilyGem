package app.familygem.geni.api

import app.familygem.geni.auth.AuthResult
import app.familygem.geni.auth.InMemoryTokenStore
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeniClientTest {

    private val server = MockWebServer()
    private val tokens = InMemoryTokenStore()
    private val profileJson = """{"id":"profile-1","first_name":"Ann","updated_at":"1700000000"}"""

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun TestScope.client() = GeniClient(
        baseUrl = server.url("/api/"),
        tokens = tokens,
        limiter = RateLimiter { testScheduler.currentTime },
        now = { 1_000L }
    )

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    @Test
    fun sendsBearerTokenAndParsesProfile() = runTest {
        tokens.save(AuthResult.Success("abc", 10_000L))
        server.enqueue(ok(profileJson))
        val profile = client().currentProfile()
        assertEquals("profile-1", profile.id)
        assertEquals("Ann", profile.firstName)
        val request = server.takeRequest()
        assertEquals("Bearer abc", request.headers["Authorization"])
        assertEquals("/api/profile", request.url.encodedPath)
    }

    @Test
    fun noTokenThrowsAuthRequiredWithoutRequest() = runTest {
        val error = runCatching { client().currentProfile() }.exceptionOrNull()
        assertTrue(error is GeniAuthRequiredException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun expiredTokenThrowsAuthRequired() = runTest {
        tokens.save(AuthResult.Success("abc", 500L))
        val error = runCatching { client().currentProfile() }.exceptionOrNull()
        assertTrue(error is GeniAuthRequiredException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun unauthorizedClearsToken() = runTest {
        tokens.save(AuthResult.Success("abc", 10_000L))
        server.enqueue(MockResponse.Builder().code(401).build())
        val error = runCatching { client().currentProfile() }.exceptionOrNull()
        assertTrue(error is GeniAuthRequiredException)
        assertNull(tokens.get())
    }

    @Test
    fun tooManyRequestsRetriesOnce() = runTest {
        tokens.save(AuthResult.Success("abc", 10_000L))
        server.enqueue(MockResponse.Builder().code(429).addHeader("X-API-Rate-Window", "10").build())
        server.enqueue(ok(profileJson))
        assertEquals("profile-1", client().currentProfile().id)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun serverErrorThrowsApiException() = runTest {
        tokens.save(AuthResult.Success("abc", 10_000L))
        server.enqueue(MockResponse.Builder().code(500).body("boom").build())
        val error = runCatching { client().currentProfile() }.exceptionOrNull()
        assertEquals(500, (error as GeniApiException).code)
    }

    @Test
    fun getJsonReturnsRawBodyOfPath() = runTest {
        tokens.save(AuthResult.Success("abc", 10_000L))
        server.enqueue(ok("""{"focus":{"id":"profile-1"}}"""))
        assertEquals("""{"focus":{"id":"profile-1"}}""", client().getJson("profile-1/immediate-family"))
        assertEquals("/api/profile-1/immediate-family", server.takeRequest().url.encodedPath)
    }

    @Test
    fun postSendsFormWithBearerToken() = runTest {
        tokens.save(AuthResult.Success("t", 10_000L))
        server.enqueue(ok(profileJson))
        client().post("profile-1/update", listOf("first_name" to "Ivan", "birth[date][year]" to "1960"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("Bearer t", request.headers["Authorization"])
        assertEquals("/api/profile-1/update", request.url.encodedPath)
        assertEquals("first_name=Ivan&birth%5Bdate%5D%5Byear%5D=1960", request.body!!.utf8())
    }

    @Test
    fun postReturnsBody() = runTest {
        tokens.save(AuthResult.Success("t", 10_000L))
        server.enqueue(ok(profileJson))
        assertEquals(profileJson, client().post("profile-1/update", listOf("occupation" to "")))
    }

    @Test
    fun post401ClearsToken() = runTest {
        tokens.save(AuthResult.Success("t", 10_000L))
        server.enqueue(MockResponse.Builder().code(401).build())
        val error = runCatching { client().post("profile-1/update", emptyList()) }.exceptionOrNull()
        assertTrue(error is GeniAuthRequiredException)
        assertNull(tokens.get())
    }

    @Test
    fun post429RetriesOnce() = runTest {
        tokens.save(AuthResult.Success("t", 10_000L))
        server.enqueue(MockResponse.Builder().code(429).addHeader("X-API-Rate-Window", "10").build())
        server.enqueue(ok(profileJson))
        assertEquals(profileJson, client().post("profile-1/update", listOf("a" to "b")))
        assertEquals(2, server.requestCount)
        server.takeRequest()
        assertEquals("a=b", server.takeRequest().body!!.utf8())
    }

    @Test
    fun post400ThrowsApiException() = runTest {
        tokens.save(AuthResult.Success("t", 10_000L))
        val body = """{"error":{"type":"ApiException","message":"Invalid union status: divorced"}}"""
        server.enqueue(MockResponse.Builder().code(400).body(body).build())
        val error = runCatching { client().post("union-1/update", listOf("status" to "divorced")) }.exceptionOrNull()
        assertEquals(400, (error as GeniApiException).code)
        assertEquals(body, error.message)
    }

    @Test
    fun requestToAnotherHostIsRefused() = runTest {
        tokens.save(app.familygem.geni.auth.AuthResult.Success("secret", Long.MAX_VALUE))
        val error = runCatching { client().getJson("https://other.example/collect") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(0, server.requestCount)
        assertTrue(runCatching { client().post("http://other.example/x", emptyList()) }.exceptionOrNull() is IllegalArgumentException)
    }
}
