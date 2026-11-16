package app.familygem.geni.sync

import app.familygem.geni.TreeHost
import app.familygem.geni.api.GeniAuthRequiredException
import app.familygem.geni.api.GeniClient
import kotlinx.coroutines.CancellationException
import org.folg.gedcom.model.Gedcom

sealed interface LoadResult {
    data class Loaded(val newPeople: Int) : LoadResult
    data object NotAGeniTree : LoadResult
    data object NeedsLogin : LoadResult
    data class Failed(val message: String) : LoadResult
}

/** Loads the immediate family of Geni profiles into FamilyGem trees. */
class GeniLoader(
    private val client: GeniClient,
    private val store: SyncStore,
    private val host: TreeHost,
    private val now: () -> Long = System::currentTimeMillis
) {

    /**
     * Fetches the immediate family of a profile (3 requests) and merges it into the tree.
     * Nothing changes if any request fails.
     */
    suspend fun load(treeId: Int, geniProfileId: String): LoadResult {
        if (!host.treeExists(treeId)) return LoadResult.NotAGeniTree
        return try {
            store.tree(treeId) ?: return LoadResult.NotAGeniTree
            val family = client.immediateFamily(geniProfileId)
            val profiles = client.profiles(family.profileIds)
            val unions = client.unions(family.unionIds)
            val existing = store.links(treeId).associateBy { it.geniId }
            val links = host.edit(treeId) { gedcom ->
                FamilyMerger.merge(
                    gedcom, treeId, family,
                    profiles.values.map { it.first }, unions.values.map { it.first },
                    profiles.mapValues { it.value.second }, unions.mapValues { it.value.second },
                    existing, now()
                )
            }
            store.saveLinks(links)
            host.refreshScreens()
            LoadResult.Loaded(newPeople = links.count { it.geniId.startsWith("profile-") && it.geniId !in existing })
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeniAuthRequiredException) {
            LoadResult.NeedsLogin
        } catch (e: Exception) {
            LoadResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Creates a Geni tree rooted at the logged-in user's profile and loads the root's immediate family.
     * @param title Tree title, by default the name of the root profile
     * @return ID of the new tree
     */
    suspend fun createTree(title: String? = null): Int {
        val root = client.currentProfile()
        val treeId = host.createTree(title ?: root.name ?: root.id, Gedcom())
        val info = GeniTreeInfo(treeId, root.id)
        store.deleteTree(treeId) // Rows left by a deleted tree that had the same ID
        store.saveTree(info)
        load(treeId, root.id)
        store.link(treeId, root.id)?.let { host.setRoot(treeId, it.gedcomId) }
        host.schedulePrefetch(treeId, root.id, info.prefetchDepth)
        return treeId
    }

    /** ID of a FamilyGem tree that still exists and is rooted at this Geni profile, if any. */
    suspend fun existingTree(rootGeniId: String): Int? =
        store.trees().firstOrNull { it.rootGeniId == rootGeniId && host.treeExists(it.treeId) }?.treeId
}
