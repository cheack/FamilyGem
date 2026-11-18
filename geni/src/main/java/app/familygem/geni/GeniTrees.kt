package app.familygem.geni

import app.familygem.geni.sync.SyncStore
import java.util.concurrent.ConcurrentHashMap

/** IDs of the Geni trees in memory, so UI code can ask on any thread without touching Room. */
object GeniTrees {
    private val ids = ConcurrentHashMap.newKeySet<Int>()

    fun isGeni(treeId: Int) = treeId in ids

    suspend fun load(store: SyncStore) {
        ids.addAll(store.trees().map { it.treeId })
    }

    fun add(treeId: Int) {
        ids.add(treeId)
    }

    fun remove(treeId: Int) {
        ids.remove(treeId)
    }

    /** For tests. */
    internal fun clear() = ids.clear()
}
