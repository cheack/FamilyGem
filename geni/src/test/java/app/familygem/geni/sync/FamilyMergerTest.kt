package app.familygem.geni.sync

import app.familygem.geni.Fixtures
import app.familygem.geni.api.GeniProfile
import app.familygem.geni.api.GeniUnion
import app.familygem.geni.api.ImmediateFamily
import app.familygem.geni.api.parseResults
import app.familygem.geni.api.rawResults
import app.familygem.geni.map.GeniMapper
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyMergerTest {

    private val profiles = parseResults(Fixtures.read("profiles-batch.json"), GeniProfile::class.java)
    private val rawProfiles = profiles.map { it.id }.zip(rawResults(Fixtures.read("profiles-batch.json"))).toMap()
    private val unions = parseResults(Fixtures.read("unions-batch.json"), GeniUnion::class.java)
    private val rawUnions = unions.map { it.id }.zip(rawResults(Fixtures.read("unions-batch.json"))).toMap()

    private fun merge(gedcom: Gedcom, fixture: String, existing: Map<String, GeniLink> = emptyMap()): List<GeniLink> {
        val family = ImmediateFamily.parse(Fixtures.read(fixture))
        return FamilyMerger.merge(
            gedcom, 7, family,
            profiles.filter { it.id in family.profileIds }, unions.filter { it.id in family.unionIds },
            rawProfiles, rawUnions, existing, nowMillis = 1_000L
        )
    }

    @Test
    fun mergeCreatesPeopleAndFamilies() {
        val gedcom = Gedcom()
        val links = merge(gedcom, "immediate-family-root.json")
        assertEquals(8, gedcom.people.size)
        assertEquals(2, gedcom.families.size)
        assertEquals(10, links.size)
        val root = links.single { it.geniId == "profile-36430" }
        assertTrue(root.fullyLoaded)
        assertEquals(1_000L, root.loadedAtMillis)
        assertFalse(links.single { it.geniId == "profile-36431" }.fullyLoaded)
        assertEquals(setOf(7), links.map { it.treeId }.toSet())
        assertTrue(links.single { it.geniId == "union-10106" }.snapshot!!.contains("union-10106"))
    }

    @Test
    fun loadingTwiceDoesNotDuplicate() {
        val gedcom = Gedcom()
        val first = merge(gedcom, "immediate-family-root.json").associateBy { it.geniId }
        val second = merge(gedcom, "immediate-family-father.json", first)
        assertEquals(8, gedcom.people.size)
        assertEquals(2, gedcom.families.size)
        assertEquals(first.getValue("profile-36431").gedcomId, second.single { it.geniId == "profile-36431" }.gedcomId)
        assertTrue(second.single { it.geniId == "profile-36431" }.fullyLoaded)
        assertTrue("root stays fully loaded", second.single { it.geniId == "profile-36430" }.fullyLoaded)
    }

    @Test
    fun mergeUpdatesInPlace() {
        val gedcom = Gedcom()
        val links = merge(gedcom, "immediate-family-root.json").associateBy { it.geniId }
        val father: Person = gedcom.getPerson(links.getValue("profile-36431").gedcomId)
        merge(gedcom, "immediate-family-root.json", links)
        assertSame(father, gedcom.getPerson(links.getValue("profile-36431").gedcomId))
    }

    @Test
    fun tagFallbackWhenLinkMissing() {
        val gedcom = Gedcom()
        merge(gedcom, "immediate-family-root.json")
        merge(gedcom, "immediate-family-root.json", existing = emptyMap())
        assertEquals(8, gedcom.people.size)
        assertEquals(8, gedcom.people.mapNotNull { GeniMapper.getGeniId(it) }.toSet().size)
    }

    @Test
    fun linkToRecordWithOtherTagIsNotTrusted() {
        val gedcom = Gedcom()
        val links = merge(gedcom, "immediate-family-root.json").associateBy { it.geniId }
        val fatherId = links.getValue("profile-36431").gedcomId
        // The user deleted the father and a hand-made person got his ID
        gedcom.people.removeIf { it.id == fatherId }
        val handMade = Person().apply { id = fatherId; addName(org.folg.gedcom.model.Name().apply { value = "Hand /Made/" }) }
        gedcom.addPerson(handMade)
        val again = merge(gedcom, "immediate-family-root.json", links).associateBy { it.geniId }
        assertEquals("Hand /Made/", handMade.names.single().value)
        assertEquals(null, GeniMapper.getGeniId(handMade))
        assertTrue(again.getValue("profile-36431").gedcomId != fatherId)
        assertEquals(9, gedcom.people.size)
    }

    @Test
    fun mergeKeepsVerifiedHash() {
        val gedcom = Gedcom()
        val first = merge(gedcom, "immediate-family-root.json").map { it.copy(verifiedHash = 42) }.associateBy { it.geniId }
        val second = merge(gedcom, "immediate-family-root.json", first)
        assertEquals(setOf<Int?>(42), second.map { it.verifiedHash }.toSet())
        assertEquals(setOf<Int?>(null), merge(Gedcom(), "immediate-family-root.json").map { it.verifiedHash }.toSet())
    }
}
