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
    override suspend fun linkByGedcomId(treeId: Int, gedcomId: String) = links(treeId).firstOrNull { it.gedcomId == gedcomId }
    override suspend fun deleteTree(treeId: Int) {
        trees.remove(treeId)
        linkMap.keys.removeAll { it.first == treeId }
        jobMap.values.removeAll { it.treeId == treeId }
    }

    val jobMap = sortedMapOf<Long, UploadJob>()
    private var lastJobId = 0L
    override suspend fun jobs(treeId: Int) = jobMap.values.filter { it.treeId == treeId }
    override suspend fun allJobs() = jobMap.values.toList()
    override suspend fun saveJob(job: UploadJob): Long {
        check(job.id != 0L || jobMap.values.none { it.treeId == job.treeId && it.key == job.key }) { "Job ${job.key} already exists" }
        val id = if (job.id == 0L) ++lastJobId else job.id
        jobMap[id] = job.copy(id = id)
        return id
    }
    override suspend fun deleteJob(id: Long) {
        jobMap.remove(id)
    }
    override suspend fun deleteJobIfVersion(id: Long, version: Int): Boolean =
        jobMap[id]?.takeIf { it.version == version }?.let { jobMap.remove(id); true } ?: false
}
