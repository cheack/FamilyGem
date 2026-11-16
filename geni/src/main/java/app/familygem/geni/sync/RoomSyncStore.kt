package app.familygem.geni.sync

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

@Entity(tableName = "tree", primaryKeys = ["treeId"])
data class GeniTreeEntity(val treeId: Int, val rootGeniId: String, val prefetchDepth: Int)

@Entity(tableName = "link", primaryKeys = ["treeId", "geniId"])
data class GeniLinkEntity(
    val treeId: Int,
    val geniId: String,
    val gedcomId: String,
    val fullyLoaded: Boolean,
    val geniUpdatedAt: String?,
    val snapshot: String?,
    val loadedAtMillis: Long
)

@Dao
interface SyncDao {
    @Query("SELECT * FROM tree WHERE treeId = :treeId")
    suspend fun tree(treeId: Int): GeniTreeEntity?

    @Upsert
    suspend fun saveTree(tree: GeniTreeEntity)

    @Query("SELECT * FROM link WHERE treeId = :treeId AND geniId = :geniId")
    suspend fun link(treeId: Int, geniId: String): GeniLinkEntity?

    @Query("SELECT * FROM link WHERE treeId = :treeId")
    suspend fun links(treeId: Int): List<GeniLinkEntity>

    @Upsert
    suspend fun saveLinks(links: List<GeniLinkEntity>)

    @Query("DELETE FROM link WHERE treeId = :treeId")
    suspend fun deleteLinks(treeId: Int)

    @Query("DELETE FROM tree WHERE treeId = :treeId")
    suspend fun deleteTreeRow(treeId: Int)
}

@Database(entities = [GeniTreeEntity::class, GeniLinkEntity::class], version = 1, exportSchema = false)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun dao(): SyncDao
}

/** Sync store in the Room database geni_sync.db. */
class RoomSyncStore(private val dao: SyncDao) : SyncStore {

    constructor(context: Context) : this(
        Room.databaseBuilder(context.applicationContext, SyncDatabase::class.java, "geni_sync.db").build().dao()
    )

    override suspend fun tree(treeId: Int) = dao.tree(treeId)?.let { GeniTreeInfo(it.treeId, it.rootGeniId, it.prefetchDepth) }

    override suspend fun saveTree(info: GeniTreeInfo) = dao.saveTree(GeniTreeEntity(info.treeId, info.rootGeniId, info.prefetchDepth))

    override suspend fun link(treeId: Int, geniId: String) = dao.link(treeId, geniId)?.toLink()

    override suspend fun links(treeId: Int) = dao.links(treeId).map { it.toLink() }

    override suspend fun saveLinks(links: List<GeniLink>) = dao.saveLinks(links.map {
        GeniLinkEntity(it.treeId, it.geniId, it.gedcomId, it.fullyLoaded, it.geniUpdatedAt, it.snapshot, it.loadedAtMillis)
    })

    override suspend fun deleteTree(treeId: Int) {
        dao.deleteLinks(treeId)
        dao.deleteTreeRow(treeId)
    }

    private fun GeniLinkEntity.toLink() = GeniLink(treeId, geniId, gedcomId, fullyLoaded, geniUpdatedAt, snapshot, loadedAtMillis)
}
