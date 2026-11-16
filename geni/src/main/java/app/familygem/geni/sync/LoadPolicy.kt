package app.familygem.geni.sync

/** Decides when the immediate family of a cached person is loaded from Geni. */
object LoadPolicy {

    const val DAY_MILLIS = 24 * 3600 * 1000L

    /** True for a linked person whose family was never loaded or was loaded more than [refreshAfterMillis] ago. */
    fun shouldLoad(link: GeniLink?, nowMillis: Long, refreshAfterMillis: Long = DAY_MILLIS): Boolean {
        link ?: return false
        return !link.fullyLoaded || nowMillis - link.loadedAtMillis > refreshAfterMillis
    }
}
