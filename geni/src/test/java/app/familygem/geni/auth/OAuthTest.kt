package app.familygem.geni.auth

import app.familygem.geni.GeniConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class OAuthTest {

    private val config = GeniConfig(appId = "123", host = "sandbox.geni.com", redirectUri = "geni123://authorize")

    @Test
    fun authorizeUrlHasMobileImplicitParams() {
        val url = OAuth.authorizeUrl(config, state = "s1").toHttpUrl()
        assertEquals("sandbox.geni.com", url.host)
        assertEquals("/platform/oauth/authorize", url.encodedPath)
        assertEquals("123", url.queryParameter("client_id"))
        assertEquals("geni123://authorize", url.queryParameter("redirect_uri"))
        assertEquals("token", url.queryParameter("response_type"))
        assertEquals("mobile", url.queryParameter("display"))
        assertEquals("s1", url.queryParameter("state"))
    }

    @Test
    fun parsesTokenFromFragment() = assertEquals(
        AuthResult.Success("abc", 1_000L + 86_400_000L),
        OAuth.parseRedirect("geni123://authorize#access_token=abc&expires_in=86400&state=s1", nowMillis = 1_000L, expectedState = "s1")
    )

    @Test
    fun parsesTokenFromQuery() = assertEquals(
        AuthResult.Success("abc", 1_000L + 86_400_000L),
        OAuth.parseRedirect("geni123://authorize?access_token=abc&expires_in=86400&state=s1", nowMillis = 1_000L, expectedState = "s1")
    )

    @Test
    fun parsesDenial() = assertEquals(
        AuthResult.Denied("user canceled"),
        OAuth.parseRedirect("geni123://authorize?status=unauthorized&message=user+canceled&state=s1", nowMillis = 0L, expectedState = "s1")
    )

    @Test
    fun missingTokenIsInvalid() =
        assertEquals(AuthResult.Invalid, OAuth.parseRedirect("geni123://authorize", nowMillis = 0L, expectedState = "s1"))

    @Test
    fun wrongStateIsInvalid() = assertEquals(
        AuthResult.Invalid,
        OAuth.parseRedirect("geni123://authorize#access_token=evil&state=other", nowMillis = 0L, expectedState = "s1")
    )

    @Test
    fun redirectWithoutPendingLoginIsInvalid() = assertEquals(
        AuthResult.Invalid,
        OAuth.parseRedirect("geni123://authorize#access_token=evil&state=s1", nowMillis = 0L, expectedState = null)
    )

    @Test
    fun missingStateIsAcceptedWhileLoginIsPending() = assertEquals(
        AuthResult.Success("abc", 86_400_000L),
        OAuth.parseRedirect("geni123://authorize#access_token=abc", nowMillis = 0L, expectedState = "s1")
    )

    @Test
    fun malformedEncodingIsInvalid() = assertEquals(
        AuthResult.Invalid,
        OAuth.parseRedirect("geni123://authorize?status=x&message=100%&state=s1", nowMillis = 0L, expectedState = "s1")
    )
}
