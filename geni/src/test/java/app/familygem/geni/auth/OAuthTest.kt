package app.familygem.geni.auth

import app.familygem.geni.GeniConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class OAuthTest {

    private val config = GeniConfig(appId = "123", host = "sandbox.geni.com", redirectUri = "geni123://authorize")

    @Test
    fun authorizeUrlHasMobileImplicitParams() {
        val url = OAuth.authorizeUrl(config).toHttpUrl()
        assertEquals("sandbox.geni.com", url.host)
        assertEquals("/platform/oauth/authorize", url.encodedPath)
        assertEquals("123", url.queryParameter("client_id"))
        assertEquals("geni123://authorize", url.queryParameter("redirect_uri"))
        assertEquals("token", url.queryParameter("response_type"))
        assertEquals("mobile", url.queryParameter("display"))
    }

    @Test
    fun parsesTokenFromFragment() = assertEquals(
        AuthResult.Success("abc", 1_000L + 86_400_000L),
        OAuth.parseRedirect("geni123://authorize#access_token=abc&expires_in=86400", nowMillis = 1_000L)
    )

    @Test
    fun parsesTokenFromQuery() = assertEquals(
        AuthResult.Success("abc", 1_000L + 86_400_000L),
        OAuth.parseRedirect("geni123://authorize?access_token=abc&expires_in=86400", nowMillis = 1_000L)
    )

    @Test
    fun parsesDenial() = assertEquals(
        AuthResult.Denied("user canceled"),
        OAuth.parseRedirect("geni123://authorize?status=unauthorized&message=user+canceled", nowMillis = 0L)
    )

    @Test
    fun missingTokenIsInvalid() =
        assertEquals(AuthResult.Invalid, OAuth.parseRedirect("geni123://authorize", nowMillis = 0L))
}
