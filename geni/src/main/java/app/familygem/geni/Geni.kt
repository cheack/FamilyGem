package app.familygem.geni

import android.content.Context
import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.RateLimiter
import app.familygem.geni.auth.EncryptedTokenStore
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Entry point of the Geni.com integration, called from upstream code through GENI-HOOK lines. */
object Geni {

    val config: GeniConfig by lazy { GeniConfig.fromBuildConfig() }

    /** True when the build has a Geni app ID (from geni.properties). */
    val isConfigured: Boolean
        get() = config.isConfigured

    /** One limiter for the whole app, because Geni counts all requests of the user together. */
    private val limiter by lazy { RateLimiter { System.currentTimeMillis() } }

    private var client: GeniClient? = null

    fun client(context: Context): GeniClient = client ?: GeniClient(
        baseUrl = "https://${config.host}/api/".toHttpUrl(),
        tokens = EncryptedTokenStore(context.applicationContext),
        limiter = limiter
    ).also { client = it }
}
