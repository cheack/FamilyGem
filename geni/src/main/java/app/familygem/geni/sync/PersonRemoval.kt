package app.familygem.geni.sync

import app.familygem.geni.TreeHost
import app.familygem.geni.map.GeniMapper

/** Takes a person out of a Geni tree on this device only; nothing is sent to Geni. */
class PersonRemoval(private val store: SyncStore, private val host: TreeHost) {

    /**
     * Removes the person of [geniId], and the families this leaves empty, from the tree and drops their links.
     * @return false if the tree has no such person
     */
    suspend fun removeFromTree(treeId: Int, geniId: String): Boolean {
        val link = store.link(treeId, geniId) ?: return false
        var emptied: List<String>? = null
        host.edit(treeId) { gedcom ->
            val person = gedcom.people.orEmpty().firstOrNull { it.id == link.gedcomId && GeniMapper.getGeniId(it) == geniId }
            if (person != null) emptied = GoneProfiles.remove(gedcom, person.id)
        }
        val families = emptied ?: return false
        store.deleteLink(treeId, geniId)
        // An edit still waiting must not reach Geni: this person is gone from the device
        store.jobs(treeId).filter { it.key == "profile:$geniId" }.forEach { store.deleteJob(it.id) }
        families.forEach { family -> store.linkByGedcomId(treeId, family)?.let { store.deleteLink(treeId, it.geniId) } }
        host.refreshScreens()
        return true
    }
}
