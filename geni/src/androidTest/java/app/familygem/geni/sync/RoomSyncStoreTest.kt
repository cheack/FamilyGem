package app.familygem.geni.sync

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomSyncStoreTest {

    private val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, SyncDatabase::class.java).build()
    private val store = RoomSyncStore(db.dao())

    @After
    fun tearDown() = db.close()

    private fun link(geniId: String, gedcomId: String, loaded: Boolean = false) = GeniLink(1, geniId, gedcomId, loaded, null, "{}", 0)

    @Test
    fun savesAndReadsTree() = runTest {
        store.saveTree(GeniTreeInfo(1, "profile-1"))
        assertEquals(GeniTreeInfo(1, "profile-1", 3), store.tree(1))
    }

    @Test
    fun upsertsLinks() = runTest {
        store.saveLinks(listOf(link("profile-1", "I1")))
        store.saveLinks(listOf(link("profile-1", "I1", loaded = true)))
        assertEquals(listOf(link("profile-1", "I1", loaded = true)), store.links(1))
    }

    @Test
    fun deleteTreeRemovesLinks() = runTest {
        store.saveTree(GeniTreeInfo(1, "profile-1"))
        store.saveLinks(listOf(link("profile-1", "I1"), link("union-1", "F1")))
        store.deleteTree(1)
        assertNull(store.tree(1))
        assertEquals(emptyList<GeniLink>(), store.links(1))
    }

    private fun job(key: String, state: JobState = JobState.PENDING) = UploadJob(
        treeId = 1, key = key, kind = JobKind.UPDATE_PROFILE, geniId = "profile-1",
        anchors = listOf("child:I3", "partner:I1"),
        params = listOf("birth[date][day]" to "", "birth[location][place_name]" to "Kazan, \"Tatarstan\"", "first_name" to "Ivan"),
        label = "Ivan", localHash = -7, state = state, error = if (state == JobState.FAILED) "Access Denied" else null
    )

    @Test
    fun jobsRoundTrip() = runTest {
        val id = store.saveJob(job("profile:profile-1", JobState.FAILED))
        assertEquals(listOf(job("profile:profile-1", JobState.FAILED).copy(id = id)), store.jobs(1))
        store.saveJob(store.jobs(1).single().copy(state = JobState.PENDING, error = null, version = 1))
        assertEquals(JobState.PENDING, store.jobs(1).single().state)
        assertEquals(1, store.jobs(1).single().version)
        val second = store.saveJob(job("add:I5").copy(treeId = 2))
        assertEquals(listOf("profile:profile-1", "add:I5"), store.allJobs().map { it.key })
        store.deleteJob(second)
        assertEquals(emptyList<UploadJob>(), store.jobs(2))
    }

    @Test
    fun deleteTreeDeletesJobs() = runTest {
        store.saveJob(job("profile:profile-1"))
        store.deleteTree(1)
        assertEquals(emptyList<UploadJob>(), store.jobs(1))
    }

    @Test
    fun linkByGedcomId() = runTest {
        store.saveLinks(listOf(link("profile-1", "I1"), link("union-1", "F1")))
        assertEquals("union-1", store.linkByGedcomId(1, "F1")?.geniId)
        assertNull(store.linkByGedcomId(1, "F2"))
    }

    @Test
    fun migratesFromVersion1() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "geni_sync_migration_test.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { v1 ->
            v1.execSQL("CREATE TABLE IF NOT EXISTS `tree` (`treeId` INTEGER NOT NULL, `rootGeniId` TEXT NOT NULL, `prefetchDepth` INTEGER NOT NULL, PRIMARY KEY(`treeId`))")
            v1.execSQL("CREATE TABLE IF NOT EXISTS `link` (`treeId` INTEGER NOT NULL, `geniId` TEXT NOT NULL, `gedcomId` TEXT NOT NULL, `fullyLoaded` INTEGER NOT NULL, `geniUpdatedAt` TEXT, `snapshot` TEXT, `loadedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`treeId`, `geniId`))")
            v1.execSQL("INSERT INTO link VALUES (1, 'profile-1', 'I1', 1, '170', '{\"id\":\"profile-1\"}', 5)")
            v1.version = 1
        }
        val migrated = Room.databaseBuilder(context, SyncDatabase::class.java, name).addMigrations(SyncDatabase.MIGRATION_1_2).build()
        try {
            val migratedStore = RoomSyncStore(migrated.dao())
            assertEquals(listOf(GeniLink(1, "profile-1", "I1", true, "170", """{"id":"profile-1"}""", 5, null)), migratedStore.links(1))
            val id = migratedStore.saveJob(job("profile:profile-1"))
            assertEquals(id, migratedStore.jobs(1).single().id)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun saveJobWithTakenKeyThrows() = runTest {
        store.saveJob(job("profile:profile-1"))
        val error = runCatching { store.saveJob(job("profile:profile-1")) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(1, store.jobs(1).size)
    }

    @Test
    fun deleteJobIfVersionKeepsNewerVersion() = runTest {
        val id = store.saveJob(job("profile:profile-1"))
        store.saveJob(store.jobs(1).single().copy(version = 1))
        assertFalse(store.deleteJobIfVersion(id, 0))
        assertEquals(1, store.jobs(1).size)
        assertTrue(store.deleteJobIfVersion(id, 1))
        assertEquals(emptyList<UploadJob>(), store.jobs(1))
    }

    @Test
    fun deleteLink() = runTest {
        store.saveLinks(listOf(link("profile-1", "I1"), link("profile-2", "I2")))
        store.deleteLink(1, "profile-1")
        assertEquals(listOf("profile-2"), store.links(1).map { it.geniId })
    }
}
