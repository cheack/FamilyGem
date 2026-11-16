package app.familygem.geni.sync

import app.familygem.geni.Fixtures
import app.familygem.geni.api.rawResults
import org.junit.Assert.assertEquals
import org.junit.Test

class RelativesIndexTest {

    private val links = rawResults(Fixtures.read("unions-batch.json")).mapIndexed { i, raw ->
        GeniLink(1, if (i == 0) "union-10105" else "union-10106", "F$i", true, null, raw, 0)
    }.filter { it.snapshot!!.contains(it.geniId) } + GeniLink(1, "profile-36430", "I1", true, null, "{}", 0)

    @Test
    fun relativesFromUnionSnapshots() = assertEquals(
        setOf("profile-36431", "profile-36432", "profile-36435", "profile-36433", "profile-36434", "profile-36436", "profile-36437"),
        RelativesIndex.relatives(links, "profile-36430").toSet()
    )

    @Test
    fun unknownPersonHasNoRelatives() = assertEquals(emptyList<String>(), RelativesIndex.relatives(links, "profile-1"))
}
