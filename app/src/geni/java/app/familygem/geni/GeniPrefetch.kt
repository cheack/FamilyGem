package app.familygem.geni

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import app.familygem.geni.sync.LoadPolicy
import app.familygem.geni.sync.Prefetcher
import app.familygem.geni.sync.RelativesIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loads Geni relatives in background, up to a number of relationship steps from a person.
 * Runs in the app process, not in WorkManager: upstream Notifier calls WorkManager.cancelAllWork() on every tree save,
 * which would cancel the work after its first load.
 */
object GeniPrefetch {

    private const val TAG = "GeniPrefetch"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutexes = mutableMapOf<Int, Mutex>()

    /** Queues a prefetch after any other prefetch of the same tree. */
    fun enqueue(context: Context, treeId: Int, startGeniId: String, depth: Int) {
        queue(context.applicationContext, treeId) { run(it, treeId, startGeniId, depth, unmeteredOnly = false) }
    }

    private fun queue(app: Context, treeId: Int, block: suspend (Context) -> Unit) {
        val mutex = synchronized(mutexes) { mutexes.getOrPut(treeId) { Mutex() } }
        scope.launch {
            mutex.withLock { block(app) }
        }
    }

    /** @return False if the pass stopped before visiting everyone */
    private suspend fun run(context: Context, treeId: Int, start: String, depth: Int, unmeteredOnly: Boolean): Boolean {
        if (!withContext(Dispatchers.Main) { AppTreeHost.treeExists(treeId) }) return true
        val store = Geni.store(context)
        val loader = Geni.loader(context)
        val began = System.currentTimeMillis()
        val prefetcher = Prefetcher(
            loader = { loader.load(treeId, it) },
            relatives = { RelativesIndex.relatives(store.unionLinks(treeId), it) },
            isFresh = { id -> store.link(treeId, id)?.let { !LoadPolicy.shouldLoad(it, System.currentTimeMillis()) } ?: false },
            mayLoad = { !unmeteredOnly || !metered(context) }
        )
        val loads = prefetcher.run(start, depth)
        Log.d(TAG, "Tree $treeId: $loads loads from $start, depth $depth, in ${System.currentTimeMillis() - began} ms" +
                if (prefetcher.completed) "" else ", stopped")
        return prefetcher.completed
    }

    private fun metered(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager?.activeNetwork == null || manager.isActiveNetworkMetered
    }

    /**
     * Refreshes the radius of every Geni tree around its root, once a day and only on an unmetered network:
     * people loaded more than a day ago are loaded again, so changes made by others on Geni reach the cache.
     */
    fun dailyRefresh(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                val metered = metered(app)
                val prefs = app.getSharedPreferences("geni_refresh", Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                for (tree in Geni.store(app).trees()) {
                    if (!withContext(Dispatchers.Main) { AppTreeHost.treeExists(tree.treeId) }) continue
                    val key = "tree${tree.treeId}"
                    // Reserved before queueing, so a second trigger (app start and network) does not queue it again
                    val previous = synchronized(this@GeniPrefetch) {
                        val last = prefs.getLong(key, -1).takeIf { it >= 0 }
                        if (!LoadPolicy.dailyRefreshDue(last, now, metered)) return@synchronized null
                        prefs.edit().putLong(key, now).commit()
                        last ?: -1L
                    } ?: continue
                    Log.d(TAG, "Daily refresh of tree ${tree.treeId}")
                    queue(app, tree.treeId) {
                        val info = Geni.store(it).tree(tree.treeId) ?: return@queue // The root may have moved meanwhile
                        if (!run(it, info.treeId, info.rootGeniId, info.prefetchDepth, unmeteredOnly = true)) {
                            // Login needed or the network became metered: due again at the next trigger
                            synchronized(this@GeniPrefetch) {
                                if (prefs.getLong(key, -1) == now) prefs.edit().putLong(key, previous).commit()
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Daily refresh failed", e)
            }
        }
    }
}
