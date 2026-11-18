package app.familygem.geni.sync

import com.google.gson.JsonParser

/** What upstream's "delete person" does for a person. */
sealed interface DeleteDecision {
    /** Not a Geni person: upstream deletes as usual (a person added offline drops its add job when saved). */
    data object Upstream : DeleteDecision
    /** The user's own profile, the root of the tree: never removed. */
    data object BlockedRoot : DeleteDecision
    /** A Geni profile: the user chooses between removing it from the device and deleting it on Geni. */
    data object Dialog : DeleteDecision
}

object DeleteRules {

    fun decide(isGeniTree: Boolean, geniId: String?, rootGeniId: String?): DeleteDecision = when {
        !isGeniTree || geniId == null -> DeleteDecision.Upstream
        geniId == rootGeniId -> DeleteDecision.BlockedRoot
        else -> DeleteDecision.Dialog
    }

    /** True if the profile's `guid` in its [snapshot] is among the [managedGuids] of the user. */
    fun isManaged(snapshot: String?, managedGuids: Set<String>): Boolean {
        val guid = try {
            snapshot?.let { JsonParser.parseString(it).asJsonObject.get("guid")?.takeIf { g -> g.isJsonPrimitive }?.asString }
        } catch (e: RuntimeException) {
            null
        }
        return guid != null && guid in managedGuids
    }
}
