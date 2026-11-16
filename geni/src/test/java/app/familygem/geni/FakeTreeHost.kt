package app.familygem.geni

import org.folg.gedcom.model.Gedcom

/** Tree host for tests: trees in memory. */
class FakeTreeHost : TreeHost {
    val trees = mutableMapOf<Int, Gedcom>()
    val titles = mutableMapOf<Int, String>()
    val roots = mutableMapOf<Int, String>()
    var refreshes = 0

    override suspend fun createTree(title: String, gedcom: Gedcom): Int {
        val id = (trees.keys.maxOrNull() ?: 0) + 1
        trees[id] = gedcom
        titles[id] = title
        return id
    }

    override suspend fun <T> edit(treeId: Int, block: (Gedcom) -> T): T = block(trees.getValue(treeId))

    override fun treeExists(treeId: Int) = treeId in trees

    override suspend fun setRoot(treeId: Int, personId: String) {
        roots[treeId] = personId
    }

    override fun refreshScreens() {
        refreshes++
    }
}
