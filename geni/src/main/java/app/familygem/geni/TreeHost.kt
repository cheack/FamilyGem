package app.familygem.geni

import org.folg.gedcom.model.Gedcom

/** What :geni needs from the app to read and write FamilyGem trees (implemented in the app module). */
interface TreeHost {

    /** Creates a FamilyGem tree with the given content (the host adds the header) and returns its ID. */
    suspend fun createTree(title: String, gedcom: Gedcom): Int

    /**
     * Runs [block] on a tree and saves it once.
     * The opened tree is edited in memory on the main thread; any other tree is read and written back.
     * Calls are serialized.
     */
    suspend fun <T> edit(treeId: Int, block: (Gedcom) -> T): T

    /** Tells the open screens that the tree changed. */
    fun refreshScreens()
}
