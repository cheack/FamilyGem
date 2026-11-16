package app.familygem.geni.map

import app.familygem.geni.api.GeniDate
import app.familygem.geni.api.GeniEvent
import app.familygem.geni.api.GeniLocation
import app.familygem.geni.api.GeniProfile
import app.familygem.geni.api.GeniUnion
import app.familygem.geni.api.geniId
import org.folg.gedcom.model.ChildRef
import org.folg.gedcom.model.EventFact
import org.folg.gedcom.model.ExtensionContainer
import org.folg.gedcom.model.Family
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.GedcomTag
import org.folg.gedcom.model.Name
import org.folg.gedcom.model.ParentFamilyRef
import org.folg.gedcom.model.Person
import org.folg.gedcom.model.SpouseFamilyRef
import org.folg.gedcom.model.SpouseRef
import org.folg.gedcom.parser.ModelParser

/**
 * Maps Geni profiles and unions into the GEDCOM model.
 * Updates existing records in place and leaves alone everything Geni does not have.
 */
object GeniMapper {

    const val GENI_ID_TAG = "_GENI_ID"
    const val ABOUT_TAG = "_GENI_ABOUT"

    /** Person facts that come from Geni and are replaced on every update. */
    private val PERSON_TAGS = setOf("SEX", "BIRT", "BAPM", "DEAT", "BURI", "OCCU")
    private val FAMILY_TAGS = setOf("MARR", "DIV")
    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    /** GEDCOM date like "12 MAR 1960", "JUN 2012", "2014" or "ABT 1960"; null without a year. */
    fun gedcomDate(date: GeniDate?): String? {
        val year = date?.year ?: return null
        val month = date.month?.takeIf { it in 1..12 }?.let { MONTHS[it - 1] }
        val day = date.day?.takeIf { month != null && it in 1..31 }
        val text = listOfNotNull(day?.toString(), month, year.toString()).joinToString(" ")
        return if (date.circa == true) "ABT $text" else text
    }

    /** Place text from the non-blank location parts, from the most specific. */
    fun place(location: GeniLocation?): String? {
        location ?: return null
        return listOf(location.placeName, location.city, location.county, location.state, location.country)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .joinToString(", ").takeIf { it.isNotEmpty() }
    }

    fun applyProfile(gedcom: Gedcom, person: Person, profile: GeniProfile) {
        person.names = names(profile)
        val facts = mutableListOf<EventFact>()
        facts += fact("SEX", value = when (profile.gender) {
            "male" -> "M"
            "female" -> "F"
            else -> "U"
        })
        event("BIRT", profile.birth)?.let { facts += it }
        event("BAPM", profile.baptism)?.let { facts += it }
        profile.occupation?.takeIf { it.isNotBlank() }?.let { facts += fact("OCCU", value = it) }
        val death = event("DEAT", profile.death)
            ?: if (profile.isAlive == false || !profile.causeOfDeath.isNullOrBlank()) fact("DEAT", value = "Y") else null
        death?.let {
            if (!profile.causeOfDeath.isNullOrBlank()) it.cause = profile.causeOfDeath
            facts += it
        }
        event("BURI", profile.burial)?.let { facts += it }
        person.eventsFacts = facts + person.eventsFacts.orEmpty().filter { it.tag !in PERSON_TAGS }
        setTag(person, ABOUT_TAG, profile.aboutMe?.takeIf { it.isNotBlank() })
    }

    private fun names(profile: GeniProfile): List<Name> {
        val given = listOfNotNull(profile.firstName, profile.middleName).filter { it.isNotBlank() }.joinToString(" ")
        val main = Name().apply {
            value = nameValue(given, profile.lastName)
            suffix = profile.suffix?.takeIf { it.isNotBlank() }
            nickname = profile.nicknames?.filter { it.isNotBlank() }?.joinToString(", ")?.takeIf { it.isNotEmpty() }
        }
        val maiden = profile.maidenName?.takeIf { it.isNotBlank() && it != profile.lastName }
            ?.let { Name().apply { value = nameValue(given, it); type = "birth" } }
        return listOfNotNull(main, maiden)
    }

    private fun nameValue(given: String, surname: String?): String =
        if (surname.isNullOrBlank()) given else "$given /$surname/".trim()

    private fun fact(tag: String, value: String? = null) = EventFact().apply { this.tag = tag; this.value = value }

    /** Event with date and place, or null when Geni has neither. */
    private fun event(tag: String, event: GeniEvent?): EventFact? {
        val date = gedcomDate(event?.date)
        val place = place(event?.location)
        if (date == null && place == null) return null
        return fact(tag).apply { this.date = date; this.place = place }
    }

    fun applyUnion(gedcom: Gedcom, family: Family, union: GeniUnion, personFor: (String) -> Person?) {
        union.partners.orEmpty().mapNotNull { personFor(geniId(it)) }.forEach { partner ->
            if (family.husbandRefs.orEmpty().any { it.ref == partner.id } || family.wifeRefs.orEmpty().any { it.ref == partner.id }) return@forEach
            val sex = partner.eventsFacts.orEmpty().firstOrNull { it.tag == "SEX" }?.value
            val asHusband = when (sex) {
                "M" -> true
                "F" -> false
                else -> family.husbandRefs.isNullOrEmpty()
            }
            val spouseRef = SpouseRef().apply { ref = partner.id }
            if (asHusband) family.addHusband(spouseRef) else family.addWife(spouseRef)
            if (partner.spouseFamilyRefs.orEmpty().none { it.ref == family.id })
                partner.addSpouseFamilyRef(SpouseFamilyRef().apply { ref = family.id })
        }
        union.children.orEmpty().mapNotNull { personFor(geniId(it)) }.forEach { child ->
            if (family.childRefs.orEmpty().none { it.ref == child.id }) family.addChild(ChildRef().apply { ref = child.id })
            if (child.parentFamilyRefs.orEmpty().none { it.ref == family.id })
                child.addParentFamilyRef(ParentFamilyRef().apply { ref = family.id })
        }
        val facts = mutableListOf<EventFact>()
        event("MARR", union.marriage)?.let { facts += it }
        (event("DIV", union.divorce) ?: if (union.status == "ex_spouse") fact("DIV", value = "Y") else null)?.let { facts += it }
        family.eventsFacts = facts + family.eventsFacts.orEmpty().filter { it.tag !in FAMILY_TAGS }
    }

    fun getGeniId(container: ExtensionContainer): String? = getTag(container, GENI_ID_TAG)

    fun setGeniId(container: ExtensionContainer, id: String) = setTag(container, GENI_ID_TAG, id)

    /** Value of a custom tag kept in the gedcom5-java "more tags" extension. */
    fun getTag(container: ExtensionContainer, tag: String): String? = moreTags(container).firstOrNull { it.tag == tag }?.value

    /** Sets or removes (with null) a custom tag. */
    fun setTag(container: ExtensionContainer, tag: String, value: String?) {
        val tags = moreTags(container).filter { it.tag != tag }.toMutableList()
        if (value != null) tags += GedcomTag(null, tag, null).apply { this.value = value }
        if (tags.isEmpty()) container.extensions?.remove(ModelParser.MORE_TAGS_EXTENSION_KEY)
        else container.putExtension(ModelParser.MORE_TAGS_EXTENSION_KEY, tags)
    }

    @Suppress("UNCHECKED_CAST")
    private fun moreTags(container: ExtensionContainer): List<GedcomTag> =
        (container.getExtension(ModelParser.MORE_TAGS_EXTENSION_KEY) as? List<*>)?.filterIsInstance<GedcomTag>().orEmpty()
}
