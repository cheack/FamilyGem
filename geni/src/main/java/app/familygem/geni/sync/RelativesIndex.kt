package app.familygem.geni.sync

import app.familygem.geni.api.GeniUnion
import app.familygem.geni.api.geniId
import com.google.gson.Gson

/** Relatives of a profile, from the union snapshots of a tree. */
object RelativesIndex {

    private val gson = Gson()

    /** Geni IDs of partners, parents, children and siblings of [geniId], as far as the cached unions tell. */
    fun relatives(links: List<GeniLink>, geniId: String): List<String> {
        val result = linkedSetOf<String>()
        for (link in links) {
            if (!link.geniId.startsWith("union-")) continue
            val union = link.snapshot?.let { runCatching { gson.fromJson(it, GeniUnion::class.java) }.getOrNull() } ?: continue
            val members = (union.partners.orEmpty() + union.children.orEmpty()).map { geniId(it) }
            if (geniId in members) result += members
        }
        result -= geniId
        return result.toList()
    }
}
