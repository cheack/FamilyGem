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

    @Test
    fun mergeClearsVerifiedHashWhenSnapshotChanges() {
        val gedcom = Gedcom()
        val first = merge(gedcom, "immediate-family-root.json")
            .map { it.copy(verifiedHash = 42, snapshot = if (it.geniId in setOf("profile-36431", "union-10105")) "{}" else it.snapshot) }
            .associateBy { it.geniId }
        val second = merge(gedcom, "immediate-family-root.json", first).associateBy { it.geniId }
        assertEquals(null, second.getValue("profile-36431").verifiedHash)
        assertEquals(null, second.getValue("union-10105").verifiedHash)
        assertEquals(42, second.getValue("profile-36430").verifiedHash)
    }

    // Three-way merge with unsent local edits: a load never overwrites them

    private val ivan = """{"id":"profile-1","first_name":"Ivan","gender":"male","is_alive":true,"occupation":"Engineer"}"""

    private fun mergeOne(gedcom: Gedcom, json: String, existing: Map<String, GeniLink> = emptyMap(), unionJson: String? = null): Map<String, GeniLink> {
        val profile = com.google.gson.Gson().fromJson(json, GeniProfile::class.java)
        val union = unionJson?.let { com.google.gson.Gson().fromJson(it, GeniUnion::class.java) }
        val family = ImmediateFamily(profile.id, listOf(profile.id), listOfNotNull(union?.id))
        return FamilyMerger.merge(gedcom, 7, family, listOf(profile), listOfNotNull(union), mapOf(profile.id to json),
            union?.let { mapOf(it.id to unionJson!!) }.orEmpty(), existing, nowMillis = 2_000L).associateBy { it.geniId }
    }

    private fun Person.fact(tag: String) = eventsFacts.orEmpty().firstOrNull { it.tag == tag }

    @Test
    fun loadKeepsQueuedEditOfSameField() {
        val gedcom = Gedcom()
        val links = mergeOne(gedcom, ivan)
        val person = gedcom.people.single()
        person.fact("OCCU")!!.value = "Driver" // Edited, not uploaded yet
        val geni = ivan.replace("Engineer", "Pilot").replace("}", ""","birth":{"date":{"year":1960}}}""")
        val again = mergeOne(gedcom, geni, links)
        assertEquals("Driver", person.fact("OCCU")!!.value)
        assertEquals(null, person.fact("BIRT")) // Nothing of the conflicting profile is applied
        assertEquals(ivan, again.getValue("profile-1").snapshot)
    }

    @Test
    fun loadAppliesOtherFieldBesideLocalEdit() {
        val gedcom = Gedcom()
        val links = mergeOne(gedcom, ivan)
        val person = gedcom.people.single()
        person.fact("OCCU")!!.value = "Driver"
        val geni = ivan.replace("}", ""","birth":{"date":{"year":1960}}}""")
        val again = mergeOne(gedcom, geni, links)
        assertEquals("Driver", person.fact("OCCU")!!.value)
        assertEquals("1960", person.fact("BIRT")!!.date)
        assertEquals(geni, again.getValue("profile-1").snapshot)
    }

    @Test
    fun loadOfEditedPersonWithoutSnapshotKeepsLocal() {
        val gedcom = Gedcom()
        val links = mergeOne(gedcom, ivan)
        val person = gedcom.people.single()
        val sentHash = app.familygem.geni.map.ProfileFields.of(person).hashCode()
        person.fact("OCCU")!!.value = "Driver"
        // Added by the app; the snapshot could not be fetched, the link holds the hash of what was sent
        val added = links.mapValues { it.value.copy(snapshot = null, verifiedHash = sentHash) }
        val geni = ivan.replace("}", ""","birth":{"date":{"year":1960}}}""")
        val again = mergeOne(gedcom, geni, added)
        assertEquals("Driver", person.fact("OCCU")!!.value)
        assertEquals(null, person.fact("BIRT"))
        assertEquals(geni, again.getValue("profile-1").snapshot)
    }

    @Test
    fun loadOfUnchangedPersonWithoutSnapshotAppliesAll() {
        val gedcom = Gedcom()
        val links = mergeOne(gedcom, ivan)
        val person = gedcom.people.single()
        val added = links.mapValues { it.value.copy(snapshot = null, verifiedHash = app.familygem.geni.map.ProfileFields.of(person).hashCode()) }
        mergeOne(gedcom, ivan.replace("Engineer", "Pilot"), added)
        assertEquals("Pilot", person.fact("OCCU")!!.value)
    }

    @Test
    fun loadKeepsQueuedMarriageEdit() {
        val union = """{"id":"union-1","status":"spouse","partners":["profile-1"],"marriage":{"date":{"year":2000}}}"""
        val gedcom = Gedcom()
        val links = mergeOne(gedcom, ivan, unionJson = union)
        val family = gedcom.families.single()
        family.eventsFacts.single { it.tag == "MARR" }.date = "2001"
        val again = mergeOne(gedcom, ivan, links, union.replace("2000", "2002"))
        assertEquals("2001", family.eventsFacts.single { it.tag == "MARR" }.date)
        assertEquals(union, again.getValue("union-1").snapshot)
        assertEquals(1, family.husbandRefs.orEmpty().size + family.wifeRefs.orEmpty().size) // Partners are still linked
    }
}
