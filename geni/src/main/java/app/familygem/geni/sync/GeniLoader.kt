package app.familygem.geni.sync

import app.familygem.geni.TreeHost
import app.familygem.geni.api.GeniAuthRequiredException
import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.GeniProfile
import app.familygem.geni.api.geniId
import app.familygem.geni.map.GeniMapper
import kotlinx.coroutines.CancellationException
import org.folg.gedcom.model.Gedcom

sealed interface LoadResult {
    /** @param profileId The profile whose family was loaded: the survivor when the requested one was merged */
    data class Loaded(val newPeople: Int, val profileId: String? = null) : LoadResult
    data object NotAGeniTree : LoadResult
    data object NeedsLogin : LoadResult
    data class Failed(val message: String) : LoadResult
    /** The profile was deleted on Geni (or merged into one already cached); [removed] if the person left the tree. */
    data class Gone(val removed: Boolean) : LoadResult
}

/** Loads the immediate family of Geni profiles into FamilyGem trees. */
class GeniLoader(
    private val client: GeniClient,
    private val store: SyncStore,
    private val host: TreeHost,
    private val now: () -> Long = System::currentTimeMillis,
    private val photos: PhotoSync? = null,
    private val onPhotoError: (String, Exception) -> Unit = { _, _ -> }
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
            profiles[geniProfileId]?.first?.takeIf { it.deleted == true }?.let { return gone(treeId, it) }
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
            syncPhotos(treeId, profiles.values.map { it.first }, links)
            host.refreshScreens()
            LoadResult.Loaded(newPeople = links.count { it.geniId.startsWith("profile-") && it.geniId !in existing }, profileId = geniProfileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeniAuthRequiredException) {
            LoadResult.NeedsLogin
        } catch (e: Exception) {
            LoadResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Main photos of the loaded profiles; a failure is reported and never fails the load. */
    private suspend fun syncPhotos(treeId: Int, profiles: List<GeniProfile>, links: List<GeniLink>) {
        val sync = photos ?: return
        val gedcomIds = links.associate { it.geniId to it.gedcomId }
        for (profile in profiles) {
            if (profile.deleted == true) continue
            val gedcomId = gedcomIds[profile.id] ?: continue
            try {
                sync.sync(treeId, profile, gedcomId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onPhotoError(profile.id, e)
            }
        }
    }

    private enum class GoneAction { MISSING, KEPT, MOVED, REMOVED }

    /**
     * A profile deleted on Geni: its person leaves the tree, unless it holds local work (the user decides through the
     * conflict of its upload). A profile merged into one not cached yet moves to it: tag and link, then a load.
     */
    private suspend fun gone(treeId: Int, profile: GeniProfile): LoadResult {
        val link = store.link(treeId, profile.id) ?: return LoadResult.Gone(removed = false)
        val survivor = profile.mergedInto?.let { geniId(it) }?.takeIf { store.link(treeId, it) == null }
        var emptied = emptyList<String>()
        val action = host.edit(treeId) { gedcom ->
            val person = gedcom.people.orEmpty().firstOrNull { it.id == link.gedcomId && GeniMapper.getGeniId(it) == profile.id }
            when {
                person == null -> GoneAction.MISSING
                GoneProfiles.hasLocalWork(person, link) -> GoneAction.KEPT
                survivor != null -> GoneAction.MOVED.also { GeniMapper.setGeniId(person, survivor) }
                else -> GoneAction.REMOVED.also { emptied = GoneProfiles.remove(gedcom, person.id) }
            }
        }
        if (action == GoneAction.KEPT) return LoadResult.Gone(removed = false)
        store.deleteLink(treeId, profile.id)
        if (action == GoneAction.MOVED) {
            store.saveLinks(listOf(GeniLink(treeId, survivor!!, link.gedcomId, false, null, null, 0)))
            store.updateRoot(treeId, profile.id, survivor)
            return load(treeId, survivor)
        }
        emptied.forEach { family -> store.linkByGedcomId(treeId, family)?.let { store.deleteLink(treeId, it.geniId) } }
        host.refreshScreens()
        return LoadResult.Gone(removed = true)
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
        app.familygem.geni.GeniTrees.add(treeId)
        load(treeId, root.id)
        store.link(treeId, root.id)?.let { host.setRoot(treeId, it.gedcomId) }
        host.schedulePrefetch(treeId, root.id, info.prefetchDepth)
        return treeId
    }

    /** ID of a FamilyGem tree that still exists and is rooted at this Geni profile, if any. */
    suspend fun existingTree(rootGeniId: String): Int? =
        store.trees().firstOrNull { it.rootGeniId == rootGeniId && host.treeExists(it.treeId) }?.treeId
}
