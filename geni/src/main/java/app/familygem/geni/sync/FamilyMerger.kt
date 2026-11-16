package app.familygem.geni.sync

import app.familygem.geni.api.GeniProfile
import app.familygem.geni.api.GeniUnion
import app.familygem.geni.api.ImmediateFamily
import app.familygem.geni.map.GeniMapper
import com.google.gson.Gson
import org.folg.gedcom.model.Family
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Person

/** Merges a loaded immediate family into a GEDCOM tree, without duplicating people or families. */
object FamilyMerger {

    /**
     * @param existing Links already stored for this tree, by Geni ID
     * @return Links of all merged profiles and unions, to be saved
     */
    fun merge(
        gedcom: Gedcom,
        treeId: Int,
        family: ImmediateFamily,
        profiles: List<GeniProfile>,
        unions: List<GeniUnion>,
        rawProfiles: Map<String, String>,
        rawUnions: Map<String, String>,
        existing: Map<String, GeniLink>,
        nowMillis: Long
    ): List<GeniLink> {
        gedcom.createIndexes()
        // Built once per merge; a link is trusted only if its record still carries the same Geni ID
        val taggedPeople = gedcom.people.orEmpty().mapNotNull { p -> GeniMapper.getGeniId(p)?.let { it to p } }.toMap()
        val taggedFamilies = gedcom.families.orEmpty().mapNotNull { f -> GeniMapper.getGeniId(f)?.let { it to f } }.toMap()
        var lastPerson = maxNumber(gedcom.people.orEmpty().map { it.id }, "I")
        var lastFamily = maxNumber(gedcom.families.orEmpty().map { it.id }, "F")
        val people = mutableMapOf<String, Person>()
        val links = mutableListOf<GeniLink>()
        for (profile in profiles) {
            val link = existing[profile.id]
            val person = link?.let { gedcom.getPerson(it.gedcomId) }?.takeIf { GeniMapper.getGeniId(it) == profile.id }
                ?: taggedPeople[profile.id]
                ?: Person().apply {
                    id = "I${++lastPerson}"
                    gedcom.addPerson(this)
                }
            // The previous Geni version counts only if the record is the linked one
            val previous = if (link != null && person.id == link.gedcomId) link.snapshot?.let { parse(it, GeniProfile::class.java) } else null
            GeniMapper.applyProfile(gedcom, person, profile, previous)
            GeniMapper.setGeniId(person, profile.id)
            people[profile.id] = person
            val isFocus = profile.id == family.focusId
            links += GeniLink(
                treeId = treeId,
                geniId = profile.id,
                gedcomId = person.id,
                fullyLoaded = isFocus || link?.fullyLoaded == true,
                geniUpdatedAt = profile.updatedAt,
                snapshot = rawProfiles[profile.id],
                loadedAtMillis = if (isFocus) nowMillis else link?.loadedAtMillis ?: 0
            )
        }
        val personFor = { geniId: String -> people[geniId] ?: taggedPeople[geniId] }
        for (union in unions) {
            val link = existing[union.id]
            val fam = link?.let { gedcom.getFamily(it.gedcomId) }?.takeIf { GeniMapper.getGeniId(it) == union.id }
                ?: taggedFamilies[union.id]
                ?: Family().apply {
                    id = "F${++lastFamily}"
                    gedcom.addFamily(this)
                }
            val previous = if (link != null && fam.id == link.gedcomId) link.snapshot?.let { parse(it, GeniUnion::class.java) } else null
            GeniMapper.applyUnion(gedcom, fam, union, previous, personFor)
            GeniMapper.setGeniId(fam, union.id)
            links += GeniLink(treeId, union.id, fam.id, true, null, rawUnions[union.id], nowMillis)
        }
        return links
    }

    private val gson = Gson()

    private fun <T> parse(json: String, type: Class<T>): T? = runCatching { gson.fromJson(json, type) }.getOrNull()

    /** Highest number used in IDs like "I12" with this prefix, 0 if none. */
    private fun maxNumber(ids: List<String>, prefix: String): Int =
        ids.mapNotNull { it.removePrefix(prefix).toIntOrNull() }.maxOrNull() ?: 0
}
