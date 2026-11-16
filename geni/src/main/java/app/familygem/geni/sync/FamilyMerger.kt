package app.familygem.geni.sync

import app.familygem.geni.api.GeniProfile
import app.familygem.geni.api.GeniUnion
import app.familygem.geni.api.ImmediateFamily
import app.familygem.geni.map.GeniMapper
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
        val people = mutableMapOf<String, Person>()
        val links = mutableListOf<GeniLink>()
        for (profile in profiles) {
            val link = existing[profile.id]
            val person = link?.let { gedcom.getPerson(it.gedcomId) }
                ?: gedcom.people.orEmpty().firstOrNull { GeniMapper.getGeniId(it) == profile.id }
                ?: Person().apply {
                    id = nextId(gedcom.people.orEmpty().map { it.id }, "I")
                    gedcom.addPerson(this)
                }
            GeniMapper.applyProfile(gedcom, person, profile)
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
        val personFor = { geniId: String ->
            people[geniId] ?: existing[geniId]?.let { gedcom.getPerson(it.gedcomId) }
        }
        for (union in unions) {
            val link = existing[union.id]
            val fam = link?.let { gedcom.getFamily(it.gedcomId) }
                ?: gedcom.families.orEmpty().firstOrNull { GeniMapper.getGeniId(it) == union.id }
                ?: Family().apply {
                    id = nextId(gedcom.families.orEmpty().map { it.id }, "F")
                    gedcom.addFamily(this)
                }
            GeniMapper.applyUnion(gedcom, fam, union, personFor)
            GeniMapper.setGeniId(fam, union.id)
            links += GeniLink(treeId, union.id, fam.id, true, null, rawUnions[union.id], nowMillis)
        }
        return links
    }

    /** Next free ID like "I12" after the highest number used with this prefix. */
    private fun nextId(ids: List<String>, prefix: String): String {
        val max = ids.mapNotNull { it.removePrefix(prefix).toIntOrNull() }.maxOrNull() ?: 0
        return "$prefix${max + 1}"
    }
}
