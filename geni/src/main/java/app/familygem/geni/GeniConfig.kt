package app.familygem.geni

/**
 * Geni app settings from geni.properties, through BuildConfig.
 * @param appId Geni application ID, used as OAuth client_id
 * @param host Geni host: www.geni.com or sandbox.geni.com
 * @param redirectUri OAuth redirect URI handled by GeniLoginActivity
 */
data class GeniConfig(val appId: String, val host: String, val redirectUri: String) {

    val isConfigured: Boolean
        get() = appId.isNotBlank()

    companion object {
        fun fromBuildConfig() = GeniConfig(BuildConfig.GENI_APP_ID, BuildConfig.GENI_HOST, BuildConfig.GENI_REDIRECT_URI)
    }
}
