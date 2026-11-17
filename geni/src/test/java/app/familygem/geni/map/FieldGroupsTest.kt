package app.familygem.geni.map

import org.junit.Assert.assertEquals
import org.junit.Test

class FieldGroupsTest {

    private val base = ProfileFields.EMPTY.copy(given = "Ivan", surname = "Petrov", occupation = "Engineer", birth = EventFields("1960", "Kyiv"))

    @Test
    fun localOnlyChangeIsNoConflict() {
        val result = threeWay(base, base.copy(occupation = "Driver"), base)
        assertEquals(setOf(ProfileGroup.OCCUPATION), result.local)
        assertEquals(emptySet<ProfileGroup>(), result.geni)
        assertEquals(emptySet<ProfileGroup>(), result.conflicts)
    }

    @Test
    fun geniOnlyChangeIsNoConflict() {
        val result = threeWay(base, base, base.copy(birth = EventFields("1961", "Kyiv")))
        assertEquals(emptySet<ProfileGroup>(), result.local)
        assertEquals(setOf(ProfileGroup.BIRTH), result.geni)
        assertEquals(emptySet<ProfileGroup>(), result.conflicts)
    }

    @Test
    fun sameValueOnBothSidesIsNoConflict() {
        val both = base.copy(occupation = "Driver")
        assertEquals(emptySet<ProfileGroup>(), threeWay(base, both, both).conflicts)
    }

    @Test
    fun differentValuesOnBothSidesConflict() {
        val result = threeWay(base, base.copy(occupation = "Driver", surname = "Ivanov"), base.copy(occupation = "Pilot", birth = null))
        assertEquals(setOf(ProfileGroup.OCCUPATION), result.conflicts)
        assertEquals(setOf(ProfileGroup.OCCUPATION, ProfileGroup.NAMES), result.local)
        assertEquals(setOf(ProfileGroup.OCCUPATION, ProfileGroup.BIRTH), result.geni)
    }

    @Test
    fun deathGroupIncludesCauseAndFlag() {
        val dead = base.copy(dead = true)
        assertEquals(setOf(ProfileGroup.DEATH), threeWay(base, dead, base.copy(causeOfDeath = "Illness")).conflicts)
    }

    @Test
    fun unionDivorceConflict() {
        val married = UnionFields(EventFields("2000", null), null)
        val result = threeWay(married, married.copy(divorce = EventFields("2010", null)), married.copy(divorce = EventFields("2011", null)))
        assertEquals(setOf(UnionGroup.DIVORCE), result.conflicts)
    }
}
