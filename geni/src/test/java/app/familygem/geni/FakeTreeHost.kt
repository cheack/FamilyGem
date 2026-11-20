package app.familygem.geni

import org.folg.gedcom.model.Gedcom
import java.io.File

/** Tree host for tests: trees in memory. */
open class FakeTreeHost : TreeHost {
    val trees = mutableMapOf<Int, Gedcom>()
    val titles = mutableMapOf<Int, String>()
    val roots = mutableMapOf<Int, String>()
    var refreshes = 0
    val opened = mutableListOf<Int>()
    val prefetches = mutableListOf<Triple<Int, String, Int>>()

    override suspend fun createTree(title: String, gedcom: Gedcom): Int {
        val id = (trees.keys.maxOrNull() ?: 0) + 1
        trees[id] = gedcom
        titles[id] = title
        return id
    }

    override suspend fun <T> edit(treeId: Int, block: (Gedcom) -> T): T = block(trees.getValue(treeId))

    override suspend fun openTree(activity: android.app.Activity, treeId: Int): Boolean {
        opened += treeId
        return true
    }

    override fun treeExists(treeId: Int) = treeId in trees

    override suspend fun setRoot(treeId: Int, personId: String) {
        roots[treeId] = personId
    }

    override fun schedulePrefetch(treeId: Int, startGeniId: String, depth: Int) {
        prefetches += Triple(treeId, startGeniId, depth)
    }

    val mediaRoot: File by lazy { kotlin.io.path.createTempDirectory("geni-media").toFile() }

    override fun mediaDir(treeId: Int) = File(mediaRoot, treeId.toString()).apply { mkdirs() }

    override fun refreshScreens() {
        refreshes++
    }
}
