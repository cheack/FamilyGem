package app.familygem.geni.sync

import app.familygem.geni.api.GeniProfile
import app.familygem.geni.api.GeniUnion
import app.familygem.geni.api.ImmediateFamily
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.map.ProfileFields
import app.familygem.geni.map.UnionFields
import app.familygem.geni.map.UnionGroup
import app.familygem.geni.map.threeWay
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
            val linked = link != null && person.id == link.gedcomId
            val previous = if (linked) link!!.snapshot?.let { parse(it, GeniProfile::class.java) } else null
            val keep = if (linked) keepLocal(link!!, ProfileFields.of(person), rawProfiles[profile.id], ProfileFields::ofSnapshot) else Keep.NONE
            if (keep == Keep.NONE) GeniMapper.applyProfile(gedcom, person, profile, previous)
            GeniMapper.setGeniId(person, profile.id)
            people[profile.id] = person
            val isFocus = profile.id == family.focusId
            val snapshot = if (keep == Keep.CONFLICT) link!!.snapshot else rawProfiles[profile.id]
            links += GeniLink(
                treeId = treeId,
                geniId = profile.id,
                gedcomId = person.id,
                fullyLoaded = isFocus || link?.fullyLoaded == true,
                geniUpdatedAt = if (keep == Keep.CONFLICT) link!!.geniUpdatedAt else profile.updatedAt,
                snapshot = snapshot,
                loadedAtMillis = if (isFocus) nowMillis else link?.loadedAtMillis ?: 0,
                verifiedHash = keptHash(link, snapshot)
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
            val linked = link != null && fam.id == link.gedcomId
            val previous = if (linked) link!!.snapshot?.let { parse(it, GeniUnion::class.java) } else null
            val keep = if (linked) keepLocal(link!!, UnionFields.of(fam), rawUnions[union.id], UnionFields::ofSnapshot) else Keep.NONE
            // Partners and children are always linked; with unsent local edits marriage and divorce stay as they are
            GeniMapper.applyUnion(gedcom, fam, union, previous, keep = if (keep == Keep.NONE) emptySet() else UnionGroup.values().toSet(), personFor = personFor)
            GeniMapper.setGeniId(fam, union.id)
            val snapshot = if (keep == Keep.CONFLICT) link!!.snapshot else rawUnions[union.id]
            links += GeniLink(treeId, union.id, fam.id, true, null, snapshot, nowMillis, keptHash(link, snapshot))
        }
        return links
    }

    /** How a load treats a linked record: apply Geni's changes, or keep the local fields because they hold unsent edits. */
    private enum class Keep {
        NONE,
        /** Geni changed a field the user also changed: nothing applied, the old snapshot stays, the upload stops as a conflict. */
        CONFLICT,
        /** Added by the app without a snapshot and edited since: the local fields stay, Geni's version becomes the snapshot. */
        LOCAL
    }

    /**
     * Three-way check of a linked record against its snapshot and Geni's version. Groups changed only on Geni are applied
     * by the normal merge (local-only edits are in other groups, so they stay); a group changed on both sides differently
     * is a conflict. Without a snapshot the hash of what the app sent tells whether the record was edited since.
     */
    private fun <F> keepLocal(link: GeniLink, local: F, raw: String?, project: (String) -> F?): Keep {
        raw ?: return Keep.NONE
        val geni = project(raw) ?: return Keep.NONE
        val snapshot = link.snapshot?.let(project)
        if (snapshot == null) return if (link.snapshot == null && link.verifiedHash != null && local.hashCode() != link.verifiedHash) Keep.LOCAL else Keep.NONE
        val conflicts = when (local) {
            is ProfileFields -> threeWay(snapshot as ProfileFields, local, geni as ProfileFields).conflicts
            is UnionFields -> threeWay(snapshot as UnionFields, local, geni as UnionFields).conflicts
            else -> emptySet()
        }
        return if (conflicts.isEmpty()) Keep.NONE else Keep.CONFLICT
    }

    /** The verified hash holds only for the snapshot it was checked against: a new snapshot needs a new check. */
    private fun keptHash(link: GeniLink?, snapshot: String?): Int? = link?.verifiedHash?.takeIf { link.snapshot == snapshot }

    private val gson = Gson()

    private fun <T> parse(json: String, type: Class<T>): T? = runCatching { gson.fromJson(json, type) }.getOrNull()

    /** Highest number used in IDs like "I12" with this prefix, 0 if none. */
    private fun maxNumber(ids: List<String>, prefix: String): Int =
        ids.mapNotNull { it.removePrefix(prefix).toIntOrNull() }.maxOrNull() ?: 0
}
