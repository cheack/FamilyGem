package app.familygem.geni.sync

/** A FamilyGem tree that mirrors part of Geni. */
data class GeniTreeInfo(val treeId: Int, val rootGeniId: String, val prefetchDepth: Int = 3)

/**
 * Link between a Geni profile or union and a GEDCOM record of a tree.
 * @param fullyLoaded The immediate family of this profile has been loaded
 * @param snapshot Raw Geni JSON of the object as last loaded
 * @param loadedAtMillis When the immediate family was last loaded, 0 if never
 * @param verifiedHash Hash of the local projection last found equal to [snapshot]; the upload planner skips the record while it matches
 * @param orphaned The record was missing from a saved tree (deleted locally): its GEDCOM ID may now belong to another record
 */
data class GeniLink(
    val treeId: Int,
    val geniId: String,
    val gedcomId: String,
    val fullyLoaded: Boolean,
    val geniUpdatedAt: String?,
    val snapshot: String?,
    val loadedAtMillis: Long,
    val verifiedHash: Int? = null,
    val orphaned: Boolean = false
)

/** Geni metadata of the trees, kept apart from upstream settings. */
interface SyncStore {
    suspend fun tree(treeId: Int): GeniTreeInfo?
    suspend fun saveTree(info: GeniTreeInfo)
    /** Moves the root of an existing tree row only if it is still [oldRoot]; false if nothing changed. */
    suspend fun updateRoot(treeId: Int, oldRoot: String, newRoot: String): Boolean
    suspend fun trees(): List<GeniTreeInfo>
    suspend fun link(treeId: Int, geniId: String): GeniLink?
    suspend fun links(treeId: Int): List<GeniLink>
    suspend fun unionLinks(treeId: Int): List<GeniLink>
    suspend fun saveLinks(links: List<GeniLink>)
    suspend fun linkByGedcomId(treeId: Int, gedcomId: String): GeniLink?
    suspend fun deleteLink(treeId: Int, geniId: String)
    /** Sets the verified hash only if the link still has this GEDCOM ID and snapshot; true if it did. */
    suspend fun setVerifiedHash(treeId: Int, geniId: String, gedcomId: String, snapshot: String?, hash: Int): Boolean
    /**
     * Replaces only the snapshot and its update time, and clears the verified hash,
     * but only if the snapshot is still [expected]; true if it was.
     */
    suspend fun updateSnapshot(treeId: Int, geniId: String, expected: String?, snapshot: String, geniUpdatedAt: String?): Boolean
    suspend fun setOrphaned(treeId: Int, geniId: String, orphaned: Boolean)
    /** Deletes the tree with its links and jobs. */
    suspend fun deleteTree(treeId: Int)

    /** Upload jobs of a tree in upload order. */
    suspend fun jobs(treeId: Int): List<UploadJob>
    suspend fun allJobs(): List<UploadJob>
    /** Inserts a job with ID 0, else replaces the job with that ID; returns the ID. */
    suspend fun saveJob(job: UploadJob): Long
    suspend fun deleteJob(id: Long)
    /** Deletes the job only if it still has this version (no newer change was planned meanwhile); true if deleted. */
    suspend fun deleteJobIfVersion(id: Long, version: Int): Boolean
}
