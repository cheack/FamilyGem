package app.familygem.geni

/** Entry point of the Geni.com integration, called from upstream code through GENI-HOOK lines. */
object Geni {

    /** True when the build has Geni app credentials (from geni.properties). */
    val isConfigured: Boolean
        get() = BuildConfig.GENI_APP_ID.isNotEmpty() && BuildConfig.GENI_APP_KEY.isNotEmpty()
}
