package app.familygem.geni.sync

/** A FamilyGem tree that mirrors part of Geni. */
data class GeniTreeInfo(val treeId: Int, val rootGeniId: String, val prefetchDepth: Int = 3)

/**
 * Link between a Geni profile or union and a GEDCOM record of a tree.
 * @param fullyLoaded The immediate family of this profile has been loaded
 * @param snapshot Raw Geni JSON of the object as last loaded
 * @param loadedAtMillis When the immediate family was last loaded, 0 if never
 */
data class GeniLink(
    val treeId: Int,
    val geniId: String,
    val gedcomId: String,
    val fullyLoaded: Boolean,
    val geniUpdatedAt: String?,
    val snapshot: String?,
    val loadedAtMillis: Long
)

/** Geni metadata of the trees, kept apart from upstream settings. */
interface SyncStore {
    suspend fun tree(treeId: Int): GeniTreeInfo?
    suspend fun saveTree(info: GeniTreeInfo)
    suspend fun trees(): List<GeniTreeInfo>
    suspend fun link(treeId: Int, geniId: String): GeniLink?
    suspend fun links(treeId: Int): List<GeniLink>
    suspend fun unionLinks(treeId: Int): List<GeniLink>
    suspend fun saveLinks(links: List<GeniLink>)
    suspend fun deleteTree(treeId: Int)
}
