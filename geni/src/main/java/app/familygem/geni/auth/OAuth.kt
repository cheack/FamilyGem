package app.familygem.geni.auth

import app.familygem.geni.GeniConfig
import okhttp3.HttpUrl
import java.net.URLDecoder
import java.util.UUID

/** Result of the OAuth redirect back to the app. */
sealed interface AuthResult {
    data class Success(val token: String, val expiresAtMillis: Long) : AuthResult
    data class Denied(val message: String) : AuthResult
    data object Invalid : AuthResult
}

/** Geni OAuth 2.0 mobile flow: implicit grant, no client secret, no refresh token. */
object OAuth {

    private const val DEFAULT_EXPIRES_IN_SECONDS = 86_400L

    /** Random value that ties a redirect to the login started by this app. */
    fun newState(): String = UUID.randomUUID().toString()

    /** URL of the Geni login page to open in the browser. */
    fun authorizeUrl(config: GeniConfig, state: String): String = HttpUrl.Builder()
        .scheme("https")
        .host(config.host)
        .addPathSegments("platform/oauth/authorize")
        .addQueryParameter("client_id", config.appId)
        .addQueryParameter("redirect_uri", config.redirectUri)
        .addQueryParameter("response_type", "token")
        .addQueryParameter("display", "mobile")
        .addQueryParameter("state", state)
        .build().toString()

    /**
     * Reads the redirect URI that Geni sends back to the app.
     * Parameters may be in the fragment or in the query string.
     * @param expectedState State of the login started by this app, null if no login is pending.
     * A redirect without a pending login or with another state is rejected (token injection).
     * A redirect without state is accepted while a login is pending, because it is not yet verified that Geni echoes state.
     */
    fun parseRedirect(uri: String, nowMillis: Long, expectedState: String?): AuthResult {
        if (expectedState == null) return AuthResult.Invalid
        val params = try {
            parameters(uri.substringAfter('?', "").substringBefore('#')) + parameters(uri.substringAfter('#', ""))
        } catch (e: IllegalArgumentException) { // Malformed percent encoding
            return AuthResult.Invalid
        }
        val state = params["state"]
        if (state != null && state != expectedState) return AuthResult.Invalid
        val token = params["access_token"]
        if (!token.isNullOrBlank()) {
            val expiresIn = params["expires_in"]?.toLongOrNull() ?: DEFAULT_EXPIRES_IN_SECONDS
            return AuthResult.Success(token, nowMillis + expiresIn * 1000)
        }
        val status = params["status"]
        if (status != null) return AuthResult.Denied(params["message"] ?: status)
        return AuthResult.Invalid
    }

    private fun parameters(text: String): Map<String, String> = text.split('&')
        .filter { it.contains('=') }
        .associate { decode(it.substringBefore('=')) to decode(it.substringAfter('=')) }

    private fun decode(text: String): String = URLDecoder.decode(text, "UTF-8")
}
