package app.familygem.geni.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
