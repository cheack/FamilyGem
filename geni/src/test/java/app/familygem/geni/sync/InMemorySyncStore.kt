package app.familygem.geni.sync

/** Sync store for tests. */
open class InMemorySyncStore : SyncStore {
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
    override suspend fun deleteLink(treeId: Int, geniId: String) {
        linkMap.remove(treeId to geniId)
    }
    override suspend fun setVerifiedHash(treeId: Int, geniId: String, gedcomId: String, snapshot: String?, hash: Int): Boolean {
        val link = linkMap[treeId to geniId]?.takeIf { it.gedcomId == gedcomId && it.snapshot == snapshot } ?: return false
        linkMap[treeId to geniId] = link.copy(verifiedHash = hash)
        return true
    }
    override suspend fun updateSnapshot(treeId: Int, geniId: String, expected: String?, snapshot: String, geniUpdatedAt: String?): Boolean {
        val link = linkMap[treeId to geniId]?.takeIf { it.snapshot == expected } ?: return false
        linkMap[treeId to geniId] = link.copy(snapshot = snapshot, geniUpdatedAt = geniUpdatedAt, verifiedHash = null)
        return true
    }
    override suspend fun forgetSnapshot(treeId: Int, geniId: String, expected: String?, hash: Int): Boolean {
        val link = linkMap[treeId to geniId]?.takeIf { it.snapshot == expected } ?: return false
        linkMap[treeId to geniId] = link.copy(snapshot = null, verifiedHash = hash)
        return true
    }
    override suspend fun setOrphaned(treeId: Int, geniId: String, orphaned: Boolean) {
        linkMap[treeId to geniId]?.let { linkMap[treeId to geniId] = it.copy(orphaned = orphaned) }
    }
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
