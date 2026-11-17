package app.familygem.geni.sync

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

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
    val loadedAtMillis: Long,
    val verifiedHash: Int?,
    @ColumnInfo(defaultValue = "0") val orphaned: Boolean
)

/** Upload job; anchors and params are JSON. */
@Entity(tableName = "job", indices = [Index(value = ["treeId", "key"], unique = true)])
data class UploadJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val treeId: Int,
    val key: String,
    val kind: String,
    val geniId: String?,
    val personGedcomId: String?,
    val familyGedcomId: String?,
    val anchors: String,
    val params: String,
    val label: String,
    val localHash: Int,
    val state: String,
    val error: String?,
    val version: Int
)

@Dao
interface SyncDao {
    @Query("SELECT * FROM tree WHERE treeId = :treeId")
    suspend fun tree(treeId: Int): GeniTreeEntity?

    @Upsert
    suspend fun saveTree(tree: GeniTreeEntity)

    @Query("SELECT * FROM tree")
    suspend fun trees(): List<GeniTreeEntity>

    @Query("SELECT * FROM link WHERE treeId = :treeId AND geniId = :geniId")
    suspend fun link(treeId: Int, geniId: String): GeniLinkEntity?

    @Query("SELECT * FROM link WHERE treeId = :treeId")
    suspend fun links(treeId: Int): List<GeniLinkEntity>

    @Query("SELECT * FROM link WHERE treeId = :treeId AND geniId LIKE 'union-%'")
    suspend fun unionLinks(treeId: Int): List<GeniLinkEntity>

    @Upsert
    suspend fun saveLinks(links: List<GeniLinkEntity>)

    @Query("DELETE FROM link WHERE treeId = :treeId")
    suspend fun deleteLinks(treeId: Int)

    @Query("DELETE FROM tree WHERE treeId = :treeId")
    suspend fun deleteTreeRow(treeId: Int)

    @Query("UPDATE link SET verifiedHash = :hash WHERE treeId = :treeId AND geniId = :geniId AND gedcomId = :gedcomId AND snapshot IS :snapshot")
    suspend fun setVerifiedHash(treeId: Int, geniId: String, gedcomId: String, snapshot: String?, hash: Int): Int

    @Query("UPDATE link SET snapshot = :snapshot, geniUpdatedAt = :geniUpdatedAt, verifiedHash = NULL WHERE treeId = :treeId AND geniId = :geniId")
    suspend fun updateSnapshot(treeId: Int, geniId: String, snapshot: String, geniUpdatedAt: String?)

    @Query("UPDATE link SET orphaned = :orphaned WHERE treeId = :treeId AND geniId = :geniId")
    suspend fun setOrphaned(treeId: Int, geniId: String, orphaned: Boolean)

    @Query("DELETE FROM link WHERE treeId = :treeId AND geniId = :geniId")
    suspend fun deleteLink(treeId: Int, geniId: String)

    @Query("SELECT * FROM link WHERE treeId = :treeId AND gedcomId = :gedcomId LIMIT 1")
    suspend fun linkByGedcomId(treeId: Int, gedcomId: String): GeniLinkEntity?

    @Query("SELECT * FROM job WHERE treeId = :treeId ORDER BY id")
    suspend fun jobs(treeId: Int): List<UploadJobEntity>

    @Query("SELECT * FROM job ORDER BY id")
    suspend fun allJobs(): List<UploadJobEntity>

    @Upsert
    suspend fun saveJob(job: UploadJobEntity): Long

    @Query("DELETE FROM job WHERE id = :id")
    suspend fun deleteJob(id: Long)

    @Query("DELETE FROM job WHERE id = :id AND version = :version")
    suspend fun deleteJobIfVersion(id: Long, version: Int): Int

    @Query("DELETE FROM job WHERE treeId = :treeId")
    suspend fun deleteJobs(treeId: Int)
}

@Database(entities = [GeniTreeEntity::class, GeniLinkEntity::class, UploadJobEntity::class], version = 3, exportSchema = false)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun dao(): SyncDao

    companion object {
        /** Version 2: upload jobs and the verified hash of links. Existing links keep their snapshots. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `link` ADD COLUMN `verifiedHash` INTEGER")
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `job` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `treeId` INTEGER NOT NULL, " +
                            "`key` TEXT NOT NULL, `kind` TEXT NOT NULL, `geniId` TEXT, `personGedcomId` TEXT, `familyGedcomId` TEXT, " +
                            "`anchors` TEXT NOT NULL, `params` TEXT NOT NULL, `label` TEXT NOT NULL, `localHash` INTEGER NOT NULL, " +
                            "`state` TEXT NOT NULL, `error` TEXT, `version` INTEGER NOT NULL)"
                )
                connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_job_treeId_key` ON `job` (`treeId`, `key`)")
            }
        }

        /** Version 3: links of records deleted locally are marked, so a reused GEDCOM ID is not taken for them. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `link` ADD COLUMN `orphaned` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Every migration, in order: the app and the tests open the database with this same list. */
        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}

/** Sync store in the Room database geni_sync.db. */
class RoomSyncStore(private val dao: SyncDao) : SyncStore {

    constructor(context: Context) : this(
        Room.databaseBuilder(context.applicationContext, SyncDatabase::class.java, "geni_sync.db")
            .addMigrations(*SyncDatabase.MIGRATIONS).build().dao()
    )

    override suspend fun tree(treeId: Int) = dao.tree(treeId)?.let { GeniTreeInfo(it.treeId, it.rootGeniId, it.prefetchDepth) }

    override suspend fun saveTree(info: GeniTreeInfo) = dao.saveTree(GeniTreeEntity(info.treeId, info.rootGeniId, info.prefetchDepth))

    override suspend fun trees() = dao.trees().map { GeniTreeInfo(it.treeId, it.rootGeniId, it.prefetchDepth) }

    override suspend fun link(treeId: Int, geniId: String) = dao.link(treeId, geniId)?.toLink()

    override suspend fun links(treeId: Int) = dao.links(treeId).map { it.toLink() }

    override suspend fun unionLinks(treeId: Int) = dao.unionLinks(treeId).map { it.toLink() }

    override suspend fun saveLinks(links: List<GeniLink>) = dao.saveLinks(links.map {
        GeniLinkEntity(it.treeId, it.geniId, it.gedcomId, it.fullyLoaded, it.geniUpdatedAt, it.snapshot, it.loadedAtMillis, it.verifiedHash, it.orphaned)
    })

    override suspend fun linkByGedcomId(treeId: Int, gedcomId: String) = dao.linkByGedcomId(treeId, gedcomId)?.toLink()

    override suspend fun deleteLink(treeId: Int, geniId: String) = dao.deleteLink(treeId, geniId)

    override suspend fun setVerifiedHash(treeId: Int, geniId: String, gedcomId: String, snapshot: String?, hash: Int) =
        dao.setVerifiedHash(treeId, geniId, gedcomId, snapshot, hash) > 0

    override suspend fun updateSnapshot(treeId: Int, geniId: String, snapshot: String, geniUpdatedAt: String?) =
        dao.updateSnapshot(treeId, geniId, snapshot, geniUpdatedAt)

    override suspend fun setOrphaned(treeId: Int, geniId: String, orphaned: Boolean) = dao.setOrphaned(treeId, geniId, orphaned)

    override suspend fun deleteTree(treeId: Int) {
        dao.deleteJobs(treeId)
        dao.deleteLinks(treeId)
        dao.deleteTreeRow(treeId)
    }

    override suspend fun jobs(treeId: Int) = dao.jobs(treeId).map { it.toJob() }

    override suspend fun allJobs() = dao.allJobs().map { it.toJob() }

    override suspend fun saveJob(job: UploadJob): Long {
        val rowId = dao.saveJob(job.toEntity())
        if (job.id != 0L) return job.id // Upsert returns -1 when it updates
        // A new job whose key is taken: the upsert fell back to updating ID 0 and stored nothing
        check(rowId > 0) { "Job ${job.key} of tree ${job.treeId} already exists" }
        return rowId
    }

    override suspend fun deleteJob(id: Long) = dao.deleteJob(id)

    override suspend fun deleteJobIfVersion(id: Long, version: Int) = dao.deleteJobIfVersion(id, version) > 0

    private fun GeniLinkEntity.toLink() = GeniLink(treeId, geniId, gedcomId, fullyLoaded, geniUpdatedAt, snapshot, loadedAtMillis, verifiedHash, orphaned)

    private val gson = Gson()
    private val anchorsType = object : TypeToken<List<String>>() {}.type
    private val paramsType = object : TypeToken<List<List<String>>>() {}.type

    private fun UploadJob.toEntity() = UploadJobEntity(
        id, treeId, key, kind.name, geniId, personGedcomId, familyGedcomId,
        gson.toJson(anchors), gson.toJson(params.map { listOf(it.first, it.second) }),
        label, localHash, state.name, error, version
    )

    private fun UploadJobEntity.toJob() = UploadJob(
        id, treeId, key, JobKind.valueOf(kind), geniId, personGedcomId, familyGedcomId,
        gson.fromJson<List<String>>(anchors, anchorsType),
        gson.fromJson<List<List<String>>>(params, paramsType).map { it[0] to it[1] },
        label, localHash, JobState.valueOf(state), error, version
    )
}
