package app.familygem.geni.sync

import app.familygem.geni.TreeHost
import app.familygem.geni.api.GeniApiException
import app.familygem.geni.api.GeniAuthRequiredException
import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.geniId
import app.familygem.geni.map.GeniMapper
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** How a job ended. */
sealed interface Outcome {
    data object Done : Outcome
    /** Temporary problem (network, Geni server error): try again later. */
    data class Retry(val reason: String) : Outcome
    /** Geni refused the change, or it cannot be sent: the user decides. */
    data class Failed(val message: String) : Outcome
    data object NeedsLogin : Outcome
}

private const val CHECK_GENI = "check on Geni whether the person was created, then retry or dismiss"

/** Runs one upload job against Geni and records the result in the sync store and the tree. */
open class Uploader(
    private val client: GeniClient,
    private val store: SyncStore,
    private val host: TreeHost,
    private val now: () -> Long = System::currentTimeMillis
) {

    open suspend fun run(job: UploadJob): Outcome = try {
        when (job.kind) {
            JobKind.UPDATE_PROFILE, JobKind.UPDATE_UNION -> update(job)
            JobKind.ADD_CHILD, JobKind.ADD_PARTNER -> add(job)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: GeniAuthRequiredException) {
        Outcome.NeedsLogin
    } catch (e: GeniApiException) {
        if (e.code in 400..499 && e.code != 408 && e.code != 429) Outcome.Failed(geniMessage(e.message))
        else Outcome.Retry("HTTP ${e.code}")
    } catch (e: IOException) {
        Outcome.Retry(e.message ?: e.javaClass.simpleName)
    } catch (e: Exception) {
        // Never throw out of the queue; adds handle their own errors after the POST, so this is an update or a lookup
        Outcome.Retry(e.message ?: e.javaClass.simpleName)
    }

    /** Sends the changed fields, then stores Geni's new version as the snapshot. The tree is not touched. */
    private suspend fun update(job: UploadJob): Outcome {
        val geniId = job.geniId ?: return Outcome.Failed("no Geni ID")
        client.post("$geniId/update", job.params)
        // Without the new snapshot a later revert of this edit would look unchanged; an update can be sent again safely
        return try {
            refreshSnapshot(job.treeId, geniId)
            Outcome.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Retry("snapshot after update: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Creates the profile on Geni under the family's union or an anchor relative, then links the person (and the family). */
    private suspend fun add(job: UploadJob): Outcome {
        val treeId = job.treeId
        val personId = job.personGedcomId ?: return Outcome.Failed("no person")
        // Already created (a finished job run again): never create a second profile
        if (store.linkByGedcomId(treeId, personId)?.geniId?.startsWith("profile-") == true) return Outcome.Done
        val familyLink = job.familyGedcomId?.let { store.linkByGedcomId(treeId, it) }?.takeIf { it.geniId.startsWith("union-") }
        suspend fun anchor(role: String): GeniLink? = job.anchors.filter { it.startsWith("$role:") }.firstNotNullOfOrNull {
            store.linkByGedcomId(treeId, it.substringAfter(':'))?.takeIf { link -> link.geniId.startsWith("profile-") }
        }
        val path = when (job.kind) {
            JobKind.ADD_CHILD -> familyLink?.let { "${it.geniId}/add-child" } ?: singleParent(job)?.let { "${it.geniId}/add-child" }
            else -> familyLink?.let { "${it.geniId}/add-partner" } ?: anchor("child")?.let { "${it.geniId}/add-parent" }
                ?: anchor("partner")?.let { "${it.geniId}/add-partner" }
        } ?: return Outcome.Failed("depends on a change that was not uploaded")

        // From here Geni may have created the profile even when the request fails: never retry automatically
        val response = try {
            client.post(path, job.params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeniAuthRequiredException) {
            return Outcome.NeedsLogin // Refused before anything was created
        } catch (e: GeniApiException) {
            if (e.code == 429) return Outcome.Retry("HTTP 429") // Not processed
            return Outcome.Failed("${geniMessage(e.message)}; $CHECK_GENI")
        } catch (e: Exception) {
            return Outcome.Failed("connection lost while adding; $CHECK_GENI")
        }
        val json = runCatching { JsonParser.parseString(response).asJsonObject }.getOrNull()
        val profileId = runCatching { json?.get("id")?.asString }.getOrNull()?.takeIf { it.startsWith("profile-") }
            ?: return Outcome.Failed("unexpected answer from Geni; $CHECK_GENI")
        val unionId = runCatching { json?.getAsJsonArray("unions")?.firstOrNull()?.asString?.let { geniId(it) } }.getOrNull()
        val familyId = job.familyGedcomId
        val linkFamily = familyLink == null && unionId != null && familyId != null

        // Links first: once stored, this person can never be added again; the tags in the tree follow
        // The hash of what was sent stands for Geni's version until a snapshot comes: an edit made meanwhile goes as an update
        val links = mutableListOf(GeniLink(treeId, profileId, personId, false, null, null, 0, verifiedHash = job.localHash))
        if (linkFamily) links += GeniLink(treeId, unionId!!, familyId!!, true, null, null, now())
        try {
            store.saveLinks(links)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Geni created the profile but this device could not record it: never send the add again by itself
            return Outcome.Failed("Geni created $profileId but the app could not save the link (${e.message}); $CHECK_GENI")
        }
        bestEffort {
            host.edit(treeId) { gedcom ->
                gedcom.people.orEmpty().firstOrNull { it.id == personId }?.let { GeniMapper.setGeniId(it, profileId) }
                if (linkFamily) gedcom.families.orEmpty().firstOrNull { it.id == familyId }?.let { GeniMapper.setGeniId(it, unionId!!) }
            }
            host.refreshScreens()
        }
        bestEffort {
            refreshSnapshot(treeId, profileId)
            (unionId ?: familyLink?.geniId)?.let { refreshSnapshot(treeId, it) }
        }
        return Outcome.Done
    }

    /**
     * The only parent of a family that has no union on Geni yet: profile-X/add-child creates the union.
     * With two parents the child must wait for (or fail with) the couple's union, never go under one parent alone.
     */
    private suspend fun singleParent(job: UploadJob): GeniLink? {
        val partners = job.anchors.filter { it.startsWith("partner:") }
        if (partners.size != 1) return null
        return store.linkByGedcomId(job.treeId, partners[0].substringAfter(':'))?.takeIf { it.geniId.startsWith("profile-") }
    }

    /**
     * Fetches the full Geni version of a linked record (write responses lack fields) and stores it as the snapshot.
     * A load may store a newer snapshot during the fetch: the write happens only over the snapshot read before it,
     * else the record is fetched again (a few times at most; then the stored snapshot is newer anyway).
     */
    private suspend fun refreshSnapshot(treeId: Int, geniId: String) {
        repeat(3) {
            val before = store.link(treeId, geniId) ?: return
            val written = if (geniId.startsWith("profile-")) {
                val (profile, raw) = client.profiles(listOf(geniId))[geniId] ?: return
                store.updateSnapshot(treeId, geniId, before.snapshot, raw, profile.updatedAt)
            } else {
                val (_, raw) = client.unions(listOf(geniId))[geniId] ?: return
                store.updateSnapshot(treeId, geniId, before.snapshot, raw, before.geniUpdatedAt)
            }
            if (written) return
        }
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The change itself is on Geni; the snapshot comes with the next load
        }
    }

    /** The message of a Geni error body like {"error":{"message":"Access Denied"}}, else the body itself (shortened). */
    private fun geniMessage(body: String?): String {
        val message = runCatching {
            JsonParser.parseString(body).asJsonObject.getAsJsonObject("error").get("message").asString
        }.getOrNull()
        return message ?: body?.take(200)?.ifBlank { null } ?: "Geni refused the change"
    }
}
