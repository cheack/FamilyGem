package app.familygem.geni.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetcherTest {

    // root - father, mother, wife, child; father - grandfather; child - grandchild; wife - child (cycle)
    private val graph = mapOf(
        "root" to listOf("father", "mother", "wife", "child"),
        "father" to listOf("root", "mother", "grandfather"),
        "mother" to listOf("root", "father"),
        "wife" to listOf("root", "child"),
        "child" to listOf("root", "wife", "grandchild"),
        "grandfather" to listOf("father"),
        "grandchild" to listOf("child")
    )
    private val loaded = mutableListOf<String>()
    private val asked = mutableListOf<String>()

    private fun prefetcher(fresh: Set<String> = emptySet(), result: (String) -> LoadResult = { LoadResult.Loaded(0) }) = Prefetcher(
        loader = { loaded += it; result(it) },
        relatives = { asked += it; graph[it].orEmpty() },
        isFresh = { it in fresh }
    )

    @Test
    fun visitsWithinDepthOnly() = runTest {
        assertEquals(1, prefetcher().run("root", depth = 1))
        assertEquals(listOf("root"), loaded)
        loaded.clear()
        prefetcher().run("root", depth = 2)
        assertEquals(setOf("root", "father", "mother", "wife", "child"), loaded.toSet())
    }

    @Test
    fun eachPersonOnce() = runTest {
        prefetcher().run("root", depth = 5)
        assertEquals(loaded.distinct(), loaded)
        assertEquals(7, loaded.size)
    }

    @Test
    fun skipsFreshPeople() = runTest {
        prefetcher(fresh = setOf("root", "father")).run("root", depth = 3)
        assertEquals(false, "root" in loaded)
        assertEquals(true, "grandfather" in loaded) // Reached through the fresh father
    }

    @Test
    fun stopsOnNeedsLogin() = runTest {
        val count = prefetcher(result = { if (it == "father") LoadResult.NeedsLogin else LoadResult.Loaded(0) }).run("root", depth = 3)
        assertEquals("father", loaded.last())
        assertEquals(1, count) // Only root was loaded
    }

    @Test
    fun lastLevelDoesNotAskRelatives() = runTest {
        prefetcher().run("root", depth = 1)
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun continuesFromTheSurvivorOfAMerge() = runTest {
        // "father" was merged into "grandfather" on Geni: its relatives are now the survivor's
        prefetcher(result = { LoadResult.Loaded(0, if (it == "father") "grandfather" else it) }).run("root", depth = 3)
        assertTrue("grandfather" in asked)
        assertEquals(false, "father" in asked)
        assertEquals(1, loaded.count { it == "grandfather" } + loaded.count { it == "father" }) // The survivor is not loaded again
    }
}
