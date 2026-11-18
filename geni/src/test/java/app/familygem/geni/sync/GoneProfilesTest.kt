package app.familygem.geni.sync

import app.familygem.geni.map.GeniMapper
import org.folg.gedcom.model.ChildRef
import org.folg.gedcom.model.Family
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Note
import org.folg.gedcom.model.Person
import org.folg.gedcom.model.SpouseRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoneProfilesTest {

    private val json = """{"id":"profile-1","first_name":"Ivan","occupation":"Engineer"}"""

    private fun person(id: String) = Person().apply {
        this.id = id
        GeniMapper.applyProfile(Gedcom(), this, com.google.gson.Gson().fromJson(json, app.familygem.geni.api.GeniProfile::class.java))
    }

    private val link = GeniLink(1, "profile-1", "I1", true, null, json, 0)

    @Test
    fun unchangedPersonHasNoLocalWork() = assertFalse(GoneProfiles.hasLocalWork(person("I1"), link))

    @Test
    fun editedFieldIsLocalWork() {
        val person = person("I1").apply { eventsFacts.single { it.tag == "OCCU" }.value = "Driver" }
        assertTrue(GoneProfiles.hasLocalWork(person, link))
    }

    @Test
    fun noteIsLocalWork() {
        val person = person("I1").apply { addNote(Note().apply { value = "Told by grandma" }) }
        assertTrue(GoneProfiles.hasLocalWork(person, link))
    }

    @Test
    fun removeDropsPersonAndFamiliesLeftEmpty() {
        val gedcom = Gedcom()
        gedcom.addPerson(person("I1"))
        gedcom.addPerson(person("I2"))
        gedcom.addFamily(Family().apply { id = "F1"; addHusband(SpouseRef().apply { ref = "I1" }); addChild(ChildRef().apply { ref = "I2" }) })
        gedcom.addFamily(Family().apply { id = "F2"; addHusband(SpouseRef().apply { ref = "I1" }) })
        assertEquals(listOf("F2"), GoneProfiles.remove(gedcom, "I1"))
        assertEquals(listOf("I2"), gedcom.people.map { it.id })
        assertEquals(listOf("F1"), gedcom.families.map { it.id })
        assertEquals(0, gedcom.families.single().husbandRefs.orEmpty().size)
    }

    @Test
    fun factGeniDoesNotHaveIsLocalWork() {
        val person = person("I1").apply { addEventFact(org.folg.gedcom.model.EventFact().apply { tag = "RESI"; place = "Kyiv" }) }
        assertTrue(GoneProfiles.hasLocalWork(person, link))
    }

    @Test
    fun removeKeepsFamiliesItDidNotEmpty() {
        val gedcom = Gedcom()
        gedcom.addPerson(person("I1"))
        gedcom.addFamily(Family().apply { id = "F1" }) // Empty already, nothing of its own
        assertEquals(emptyList<String>(), GoneProfiles.remove(gedcom, "I1"))
        assertEquals(listOf("F1"), gedcom.families.map { it.id })
    }

    @Test
    fun removeKeepsAnEmptiedFamilyWithItsOwnData() {
        val gedcom = Gedcom()
        gedcom.addPerson(person("I1"))
        gedcom.addFamily(Family().apply { id = "F1"; addHusband(SpouseRef().apply { ref = "I1" }); addNote(Note().apply { value = "Wedding story" }) })
        assertEquals(emptyList<String>(), GoneProfiles.remove(gedcom, "I1"))
        assertEquals("Wedding story", gedcom.families.single().notes.single().value)
        assertEquals(0, gedcom.families.single().husbandRefs.orEmpty().size)
    }

    @Test
    fun extraNameIsLocalWork() {
        val person = person("I1").apply { addName(org.folg.gedcom.model.Name().apply { value = "Vanya //" }) }
        assertTrue(GoneProfiles.hasLocalWork(person, link))
    }

    @Test
    fun occupationPlaceIsLocalWork() {
        val person = person("I1").apply { eventsFacts.single { it.tag == "OCCU" }.place = "Kyiv" }
        assertTrue(GoneProfiles.hasLocalWork(person, link))
    }

    @Test
    fun removeKeepsAnEmptiedFamilyWithItsOwnEvent() {
        val gedcom = Gedcom()
        gedcom.addPerson(person("I1"))
        gedcom.addFamily(Family().apply {
            id = "F1"; addHusband(SpouseRef().apply { ref = "I1" })
            addEventFact(org.folg.gedcom.model.EventFact().apply { tag = "ANUL"; date = "1990" })
        })
        assertEquals(emptyList<String>(), GoneProfiles.remove(gedcom, "I1"))
        assertEquals(listOf("F1"), gedcom.families.map { it.id })
    }
}
