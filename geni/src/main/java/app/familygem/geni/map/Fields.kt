package app.familygem.geni.map

import app.familygem.geni.api.GeniProfile
import app.familygem.geni.api.GeniUnion
import com.google.gson.Gson
import org.folg.gedcom.model.EventFact
import org.folg.gedcom.model.Family
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Person

/*
 * Projections of GEDCOM records to the fields Geni has, in normalized GEDCOM form.
 * The local record and its Geni snapshot (mapped through GeniMapper) are projected the same way,
 * so a value that round-trips through Geni compares equal and makes no upload.
 */

/** Date and place of an event; the date is canonical when Geni can store it, else the trimmed text. */
data class EventFields(val date: String?, val place: String?)

data class ProfileFields(
    val given: String?,
    val surname: String?,
    val maiden: String?,
    val suffix: String?,
    val nicknames: String?,
    val sex: String?,
    val birth: EventFields?,
    val baptism: EventFields?,
    val death: EventFields?,
    val burial: EventFields?,
    val dead: Boolean,
    val causeOfDeath: String?,
    val occupation: String?,
    val about: String?
) {
    companion object {
        val EMPTY = ProfileFields(null, null, null, null, null, null, null, null, null, null, false, null, null, null)

        /** Reads what [GeniMapper.applyProfile] writes: first main name, birth name, first fact of each tag. */
        fun of(person: Person): ProfileFields {
            val names = person.names.orEmpty()
            val main = names.firstOrNull { it.type != "birth" }
            val (given, surname) = splitName(main?.value)
            val facts = person.eventsFacts.orEmpty()
            fun first(tag: String) = facts.firstOrNull { it.tag == tag }
            val death = first("DEAT")
            return ProfileFields(
                given = given,
                surname = surname,
                // GeniMapper writes no birth name when the maiden name equals the surname
                maiden = splitName(names.firstOrNull { it.type == "birth" }?.value).second?.takeIf { it != surname },
                suffix = clean(main?.suffix),
                nicknames = main?.nickname?.split(',')?.mapNotNull { clean(it) }?.joinToString(", ")?.takeIf { it.isNotEmpty() },
                sex = clean(first("SEX")?.value)?.uppercase()?.takeIf { it == "M" || it == "F" },
                birth = event(first("BIRT")),
                baptism = event(first("BAPM")),
                death = event(death),
                burial = event(first("BURI")),
                dead = death != null,
                causeOfDeath = clean(death?.cause),
                occupation = clean(first("OCCU")?.value),
                about = GeniMapper.getTag(person, GeniMapper.ABOUT_TAG)?.replace("\r\n", "\n")?.trim()?.takeIf { it.isNotEmpty() } // Keeps line breaks
            )
        }

        /** Projection of a raw Geni profile, as the loader would map it; null if the JSON is not a profile. */
        fun ofSnapshot(json: String): ProfileFields? {
            val profile = parse(json, GeniProfile::class.java) ?: return null
            val person = Person()
            GeniMapper.applyProfile(Gedcom(), person, profile)
            return of(person)
        }
    }
}

data class UnionFields(val marriage: EventFields?, val divorce: EventFields?) {
    companion object {
        val EMPTY = UnionFields(null, null)

        /** A divorce without date and place ("DIV Y") counts as no divorce event: Geni would need `status` for it. */
        fun of(family: Family): UnionFields {
            val facts = family.eventsFacts.orEmpty()
            return UnionFields(event(facts.firstOrNull { it.tag == "MARR" }), event(facts.firstOrNull { it.tag == "DIV" }))
        }

        fun ofSnapshot(json: String): UnionFields? {
            val union = parse(json, GeniUnion::class.java) ?: return null
            val family = Family()
            GeniMapper.applyUnion(Gedcom(), family, union) { null }
            return of(family)
        }
    }
}

private val gson = Gson()

private fun <T : Any> parse(json: String, type: Class<T>): T? =
    runCatching { gson.fromJson(json, type) }.getOrNull()

/** Trimmed text with single spaces, null if blank. */
internal fun clean(text: String?): String? = text?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }

/** Given name and surname of a GEDCOM name value like "Ivan /Petrov/". */
private fun splitName(value: String?): Pair<String?, String?> {
    value ?: return null to null
    val start = value.indexOf('/')
    if (start < 0) return clean(value) to null
    val end = value.indexOf('/', start + 1).let { if (it < 0) value.length else it }
    return clean(value.substring(0, start)) to clean(value.substring(start + 1, end))
}

private fun event(fact: EventFact?): EventFields? {
    fact ?: return null
    val text = clean(fact.date)
    val date = GeniParams.parseDate(text)?.let { GeniMapper.gedcomDate(it) } ?: text
    val place = clean(fact.place)
    return if (date == null && place == null) null else EventFields(date, place)
}
