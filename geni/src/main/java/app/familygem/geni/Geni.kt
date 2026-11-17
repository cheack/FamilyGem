package app.familygem.geni

import android.content.Context
import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.RateLimiter
import app.familygem.geni.auth.EncryptedTokenStore
import app.familygem.geni.sync.GeniLoader
import app.familygem.geni.sync.RoomSyncStore
import app.familygem.geni.sync.SyncQueue
import app.familygem.geni.sync.SyncStore
import app.familygem.geni.sync.Uploader
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
    private var store: SyncStore? = null

    /** Access to FamilyGem trees, set by the app glue. */
    @Volatile
    var host: TreeHost? = null

    fun client(context: Context): GeniClient = client ?: GeniClient(
        baseUrl = "https://${config.host}/api/".toHttpUrl(),
        tokens = EncryptedTokenStore(context.applicationContext),
        limiter = limiter
    ).also { client = it }

    @Synchronized
    fun store(context: Context): SyncStore = store ?: RoomSyncStore(context.applicationContext).also { store = it }

    private var queue: SyncQueue? = null

    /** The upload queue; one instance, so its per-tree locks are shared. */
    @Synchronized
    fun queue(context: Context): SyncQueue = queue ?: SyncQueue(
        store(context),
        Uploader(client(context), store(context), host ?: error("Geni tree host not installed"))
    ).also { queue = it }

    fun loader(context: Context): GeniLoader =
        GeniLoader(client(context), store(context), host ?: error("Geni tree host not installed"))
}
