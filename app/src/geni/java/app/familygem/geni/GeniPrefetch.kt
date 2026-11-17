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
        val appContext = context.applicationContext
        val mutex = synchronized(mutexes) { mutexes.getOrPut(treeId) { Mutex() } }
        scope.launch {
            mutex.withLock { run(appContext, treeId, startGeniId, depth) }
        }
    }

    private suspend fun run(context: Context, treeId: Int, start: String, depth: Int) {
        if (!AppTreeHost.treeExists(treeId)) return
        val store = Geni.store(context)
        val loader = Geni.loader(context)
        val began = System.currentTimeMillis()
        val loads = Prefetcher(
            loader = { loader.load(treeId, it) },
            relatives = { RelativesIndex.relatives(store.unionLinks(treeId), it) },
            isFresh = { id -> store.link(treeId, id)?.let { !LoadPolicy.shouldLoad(it, System.currentTimeMillis()) } ?: false }
        ).run(start, depth)
        Log.d(TAG, "Tree $treeId: $loads loads from $start, depth $depth, in ${System.currentTimeMillis() - began} ms")
    }

    /**
     * Refreshes the radius of every Geni tree around its root, once a day and only on an unmetered network:
     * people loaded more than a day ago are loaded again, so changes made by others on Geni reach the cache.
     */
    fun dailyRefresh(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                val manager = app.getSystemService(ConnectivityManager::class.java)
                val metered = manager?.activeNetwork == null || manager.isActiveNetworkMetered
                val prefs = app.getSharedPreferences("geni_refresh", Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                for (tree in Geni.store(app).trees()) {
                    if (!AppTreeHost.treeExists(tree.treeId)) continue
                    val key = "tree${tree.treeId}"
                    if (!LoadPolicy.dailyRefreshDue(prefs.getLong(key, -1).takeIf { it >= 0 }, now, metered)) continue
                    prefs.edit().putLong(key, now).apply()
                    Log.d(TAG, "Daily refresh of tree ${tree.treeId}")
                    enqueue(app, tree.treeId, tree.rootGeniId, tree.prefetchDepth)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Daily refresh failed", e)
            }
        }
    }
}
