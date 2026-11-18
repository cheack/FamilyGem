package app.familygem.geni

import app.familygem.geni.sync.GeniTreeInfo
import app.familygem.geni.sync.InMemorySyncStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeniTreesTest {

    @After
    fun clear() = GeniTrees.clear()

    @Test
    fun `unknown before load`() {
        assertFalse(GeniTrees.isGeni(3))
    }

    @Test
    fun `load marks the trees of the store`() {
        val store = InMemorySyncStore()
        runBlocking {
            store.saveTree(GeniTreeInfo(3, "profile-1"))
            store.saveTree(GeniTreeInfo(5, "profile-2"))
            GeniTrees.load(store)
        }
        assertTrue(GeniTrees.isGeni(3))
        assertTrue(GeniTrees.isGeni(5))
        assertFalse(GeniTrees.isGeni(4))
    }

    @Test
    fun `add and remove`() {
        GeniTrees.add(9)
        assertTrue(GeniTrees.isGeni(9))
        GeniTrees.remove(9)
        assertFalse(GeniTrees.isGeni(9))
    }
}
