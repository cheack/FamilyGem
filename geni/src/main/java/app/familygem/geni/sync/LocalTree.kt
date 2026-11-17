package app.familygem.geni.sync

import app.familygem.geni.map.GeniMapper
import app.familygem.geni.map.ProfileFields
import app.familygem.geni.map.UnionFields
import org.folg.gedcom.model.Gedcom

/** A person of a saved tree, reduced to what the upload planner needs. */
data class LocalPerson(val gedcomId: String, val geniId: String?, val label: String, val fields: ProfileFields)

/** A family of a saved tree: partners (husbands then wives) and children by GEDCOM ID. */
data class LocalFamily(
    val gedcomId: String,
    val geniId: String?,
    val partners: List<String>,
    val children: List<String>,
    val fields: UnionFields
)

/** Snapshot of a saved tree taken on the saving thread, so planning can run later without touching the live tree. */
data class LocalTree(val people: List<LocalPerson>, val families: List<LocalFamily>) {
    companion object {
        fun capture(gedcom: Gedcom): LocalTree = LocalTree(
            people = gedcom.people.orEmpty().map { person ->
                val fields = ProfileFields.of(person)
                val label = listOfNotNull(fields.given, fields.surname).joinToString(" ").ifEmpty { person.id }
                LocalPerson(person.id, GeniMapper.getGeniId(person), label, fields)
            },
            families = gedcom.families.orEmpty().map { family ->
                LocalFamily(
                    gedcomId = family.id,
                    geniId = GeniMapper.getGeniId(family),
                    partners = family.husbandRefs.orEmpty().map { it.ref } + family.wifeRefs.orEmpty().map { it.ref },
                    children = family.childRefs.orEmpty().map { it.ref },
                    fields = UnionFields.of(family)
                )
            }
        )
    }
}
