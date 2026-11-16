package app.familygem.geni.auth

import app.familygem.geni.GeniConfig
import okhttp3.HttpUrl
import java.net.URLDecoder

/** Result of the OAuth redirect back to the app. */
sealed interface AuthResult {
    data class Success(val token: String, val expiresAtMillis: Long) : AuthResult
    data class Denied(val message: String) : AuthResult
    data object Invalid : AuthResult
}

/** Geni OAuth 2.0 mobile flow: implicit grant, no client secret, no refresh token. */
object OAuth {

    private const val DEFAULT_EXPIRES_IN_SECONDS = 86_400L

    /** URL of the Geni login page to open in the browser. */
    fun authorizeUrl(config: GeniConfig): String = HttpUrl.Builder()
        .scheme("https")
        .host(config.host)
        .addPathSegments("platform/oauth/authorize")
        .addQueryParameter("client_id", config.appId)
        .addQueryParameter("redirect_uri", config.redirectUri)
        .addQueryParameter("response_type", "token")
        .addQueryParameter("display", "mobile")
        .build().toString()

    /**
     * Reads the redirect URI that Geni sends back to the app.
     * Parameters may be in the fragment or in the query string.
     */
    fun parseRedirect(uri: String, nowMillis: Long): AuthResult {
        val params = parameters(uri.substringAfter('?', "").substringBefore('#')) +
                parameters(uri.substringAfter('#', ""))
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
