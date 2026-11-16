package app.familygem.geni

import app.familygem.Global
import app.familygem.Settings
import app.familygem.util.TreeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.folg.gedcom.model.Gedcom

/** Tree host on top of FamilyGem's settings and JSON tree files. */
object AppTreeHost : TreeHost {

    private val mutex = Mutex()

    override suspend fun createTree(title: String, gedcom: Gedcom): Int = mutex.withLock {
        val treeId = Global.settings.max() + 1
        gedcom.header = TreeUtil.createHeader("$treeId.json")
        gedcom.createIndexes()
        // The tree must be in the settings before saveJson(), which updates its birthday notifications
        withContext(Dispatchers.Main) {
            Global.settings.addTree(Settings.Tree(treeId, title, 0, 0, null, null, null, 0))
            Global.settings.save()
        }
        TreeUtil.saveJson(gedcom, treeId, false)
        treeId
    }

    override suspend fun <T> edit(treeId: Int, block: (Gedcom) -> T): T = mutex.withLock {
        val opened = Global.gc?.takeIf { Global.settings.openTree == treeId }
        val gedcom = opened ?: TreeUtil.openGedcomTemporarily(treeId, false) ?: error("Tree $treeId not readable")
        // The opened tree is shared with the screens, which change it on the main thread
        val result = withContext(Dispatchers.Main) { block(gedcom) }
        TreeUtil.saveJson(gedcom, treeId, false)
        withContext(Dispatchers.Main) {
            Global.settings.getTree(treeId)?.let { TreeUtil.refreshData(gedcom, it) }
        }
        result
    }

    override fun treeExists(treeId: Int) = Global.settings.getTree(treeId) != null

    override suspend fun setRoot(treeId: Int, personId: String) = withContext(Dispatchers.Main) {
        Global.settings.getTree(treeId)?.let {
            it.root = personId
            Global.settings.save()
        }
        Unit
    }

    override fun schedulePrefetch(treeId: Int, startGeniId: String, depth: Int) =
        GeniPrefetch.enqueue(Global.context, treeId, startGeniId, depth)

    override fun refreshScreens() {
        Global.edited = true
    }
}
