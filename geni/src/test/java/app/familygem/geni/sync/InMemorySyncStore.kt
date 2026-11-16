package app.familygem.geni.sync

/** Sync store for tests. */
class InMemorySyncStore : SyncStore {
    val trees = mutableMapOf<Int, GeniTreeInfo>()
    val linkMap = linkedMapOf<Pair<Int, String>, GeniLink>()
    override suspend fun tree(treeId: Int) = trees[treeId]
    override suspend fun saveTree(info: GeniTreeInfo) {
        trees[info.treeId] = info
    }
    override suspend fun trees() = trees.values.toList()
    override suspend fun link(treeId: Int, geniId: String) = linkMap[treeId to geniId]
    override suspend fun links(treeId: Int) = linkMap.values.filter { it.treeId == treeId }
    override suspend fun unionLinks(treeId: Int) = links(treeId).filter { it.geniId.startsWith("union-") }
    override suspend fun saveLinks(links: List<GeniLink>) = links.forEach { linkMap[it.treeId to it.geniId] = it }
    override suspend fun deleteTree(treeId: Int) {
        trees.remove(treeId)
        linkMap.keys.removeAll { it.first == treeId }
    }
}
