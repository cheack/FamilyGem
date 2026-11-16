package app.familygem.geni

import android.content.Context
import android.util.Log
import app.familygem.geni.sync.LoadPolicy
import app.familygem.geni.sync.Prefetcher
import app.familygem.geni.sync.RelativesIndex
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
}
