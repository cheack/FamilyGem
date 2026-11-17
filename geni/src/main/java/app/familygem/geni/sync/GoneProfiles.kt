package app.familygem.geni.sync

import app.familygem.geni.map.ProfileFields
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Person

/** People whose Geni profile was deleted or merged into another one. */
object GoneProfiles {

    /**
     * True if the person holds something the user may want to keep: edits of Geni fields not uploaded yet,
     * or data Geni does not have (notes, sources, media, on the person or on its facts).
     */
    fun hasLocalWork(person: Person, link: GeniLink): Boolean {
        val facts = person.eventsFacts.orEmpty()
        val localData = person.notes.orEmpty().isNotEmpty() || person.noteRefs.orEmpty().isNotEmpty() ||
                person.sourceCitations.orEmpty().isNotEmpty() || person.media.orEmpty().isNotEmpty() || person.mediaRefs.orEmpty().isNotEmpty() ||
                facts.any { it.notes.orEmpty().isNotEmpty() || it.noteRefs.orEmpty().isNotEmpty() || it.sourceCitations.orEmpty().isNotEmpty() }
        if (localData) return true
        val local = ProfileFields.of(person)
        val snapshot = link.snapshot?.let { ProfileFields.ofSnapshot(it) }
        return if (snapshot != null) snapshot != local else link.verifiedHash != null && link.verifiedHash != local.hashCode()
    }

    /** Removes a person from the tree and from its families, and the families left without anyone; returns their IDs. */
    fun remove(gedcom: Gedcom, personId: String): List<String> {
        gedcom.people = gedcom.people.orEmpty().filter { it.id != personId }.toMutableList()
        val emptied = mutableListOf<String>()
        for (family in gedcom.families.orEmpty()) {
            family.husbandRefs = family.husbandRefs.orEmpty().filter { it.ref != personId }.toMutableList()
            family.wifeRefs = family.wifeRefs.orEmpty().filter { it.ref != personId }.toMutableList()
            family.childRefs = family.childRefs.orEmpty().filter { it.ref != personId }.toMutableList()
            if (family.husbandRefs.isEmpty() && family.wifeRefs.isEmpty() && family.childRefs.isEmpty()) emptied += family.id
        }
        gedcom.families = gedcom.families.orEmpty().filter { it.id !in emptied }.toMutableList()
        gedcom.createIndexes()
        return emptied
    }
}
