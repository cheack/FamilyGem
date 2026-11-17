package app.familygem.geni.sync

import app.familygem.geni.FakeTreeHost
import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.RateLimiter
import app.familygem.geni.auth.InMemoryTokenStore
import app.familygem.geni.map.ProfileFields
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import app.familygem.geni.map.UnionFields
import org.junit.Test

class SyncQueueTest {

    private val ivanJson = """{"id":"profile-1","first_name":"Ivan","last_name":"User","occupation":"Engineer","is_alive":true}"""
    private val annaJson = """{"id":"profile-2","first_name":"Anna","last_name":"User","is_alive":true}"""
    private val ivan = ProfileFields.ofSnapshot(ivanJson)!!
    private val anna = ProfileFields.ofSnapshot(annaJson)!!
    private val store = InMemorySyncStore()

    /** Uploader that answers from a script instead of Geni. */
    private inner class ScriptedUploader : Uploader(
        GeniClient("http://localhost/api/".toHttpUrl(), InMemoryTokenStore(), RateLimiter { 0L }), store, FakeTreeHost()
    ) {
        val ran = mutableListOf<UploadJob>()
        /** Like Geni: an update changes the profile, and the uploader stores the new version as the snapshot. */
        val geniApplies: suspend (UploadJob) -> Outcome = { job ->
            if (job.kind == JobKind.UPDATE_PROFILE) {
                val link = store.link(1, job.geniId!!)!!
                val json = JsonParser.parseString(link.snapshot).asJsonObject
                job.params.forEach { (key, value) -> json.addProperty(key, value) }
                store.saveLinks(listOf(link.copy(snapshot = json.toString(), verifiedHash = null)))
            }
            Outcome.Done
        }
        var answer: suspend (UploadJob) -> Outcome = geniApplies
        override suspend fun run(job: UploadJob): Outcome {
            ran += job
            return answer(job)
        }
    }

    private val uploader = ScriptedUploader()
    private val queue = SyncQueue(store, uploader)

    private suspend fun linkBoth() = store.saveLinks(listOf(
        GeniLink(1, "profile-1", "I1", true, null, ivanJson, 0),
        GeniLink(1, "profile-2", "I2", true, null, annaJson, 0)
    ))

    private fun tree(ivanFields: ProfileFields = ivan, annaFields: ProfileFields = anna) = LocalTree(
        listOf(LocalPerson("I1", "profile-1", "Ivan User", ivanFields), LocalPerson("I2", "profile-2", "Anna User", annaFields)),
        emptyList()
    )

    private val driver = ivan.copy(occupation = "Driver")
    private val pilot = ivan.copy(occupation = "Pilot")

    @Test
    fun samePlanTwiceKeepsOneJob() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver))
        queue.onSaved(1, tree(driver))
        val job = store.jobs(1).single()
        assertEquals(listOf("occupation" to "Driver"), job.params)
        assertEquals(0, job.version)
    }

    @Test
    fun unchangedTreeVerifiesLinks() = runTest {
        linkBoth()
        queue.onSaved(1, tree())
        assertEquals(ivan.hashCode(), store.link(1, "profile-1")!!.verifiedHash)
        assertEquals(emptyList<UploadJob>(), store.jobs(1))
    }

    @Test
    fun verifiedHashIsNotWrittenOverANewerSnapshot() = runTest {
        linkBoth()
        val plan = UploadPlanner.plan(1, tree(), store.links(1), emptyList())
        // A load replaces the snapshot after planning read the links
        store.saveLinks(listOf(store.link(1, "profile-1")!!.copy(snapshot = """{"id":"profile-1","first_name":"Ivan2"}""")))
        queue.saveVerified(plan.verified)
        assertNull(store.link(1, "profile-1")!!.verifiedHash)
        assertEquals(anna.hashCode(), store.link(1, "profile-2")!!.verifiedHash)
    }

    @Test
    fun changedParamsResetFailedJob() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver))
        val failed = store.jobs(1).single().copy(state = JobState.FAILED, error = "Access Denied")
        store.saveJob(failed)
        queue.onSaved(1, tree(pilot))
        val job = store.jobs(1).single()
        assertEquals(failed.id, job.id)
        assertEquals(JobState.PENDING, job.state)
        assertNull(job.error)
        assertEquals(1, job.version)
        assertEquals(listOf("occupation" to "Pilot"), job.params)
    }

    @Test
    fun dismissedJobStaysDismissedForSameParams() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver))
        store.saveJob(store.jobs(1).single().copy(state = JobState.FAILED, error = "x"))
        queue.dismissFailed()
        queue.onSaved(1, tree(driver))
        assertEquals(JobState.DISMISSED, store.jobs(1).single().state)
        queue.onSaved(1, tree(pilot))
        assertEquals(JobState.PENDING, store.jobs(1).single().state)
    }

    @Test
    fun retryFailedMakesJobsPending() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver))
        store.saveJob(store.jobs(1).single().copy(state = JobState.FAILED, error = "x"))
        queue.retryFailed()
        assertEquals(JobState.PENDING, store.jobs(1).single().state)
        assertNull(store.jobs(1).single().error)
    }

    @Test
    fun runsJobsInOrderAndDeletesDone() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver, anna.copy(occupation = "Doctor")))
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        assertEquals(listOf("profile:profile-1", "profile:profile-2"), uploader.ran.map { it.key })
        assertEquals(emptyList<UploadJob>(), store.jobs(1))
    }

    @Test
    fun editDuringUploadKeepsNewerVersion() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        uploader.answer = { job ->
            if (uploader.ran.size == 1) {
                started.complete(Unit)
                release.await()
            }
            uploader.geniApplies(job)
        }
        val run = async { queue.runPending(1) }
        started.await()
        queue.onSaved(1, tree(pilot)) // The user edits again while the first version uploads
        release.complete(Unit)
        assertEquals(RunResult.EMPTY, run.await())
        assertEquals(listOf(listOf("occupation" to "Driver"), listOf("occupation" to "Pilot")), uploader.ran.map { it.params })
        assertEquals(emptyList<UploadJob>(), store.jobs(1))
    }

    @Test
    fun transientErrorKeepsJobPending() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver))
        uploader.answer = { Outcome.Retry("HTTP 503") }
        assertEquals(RunResult.RETRY_LATER, queue.runPending(1))
        assertEquals(JobState.PENDING, store.jobs(1).single().state)
    }

    @Test
    fun authErrorStopsTheQueue() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver, anna.copy(occupation = "Doctor")))
        uploader.answer = { Outcome.NeedsLogin }
        assertEquals(RunResult.NEEDS_LOGIN, queue.runPending(1))
        assertEquals(1, uploader.ran.size)
        assertEquals(2, store.jobs(1).count { it.state == JobState.PENDING })
    }

    @Test
    fun failedJobIsKeptAndSkipped() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver, anna.copy(occupation = "Doctor")))
        uploader.answer = { job -> if (job.key == "profile:profile-1") Outcome.Failed("Access Denied") else uploader.geniApplies(job) }
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        val job = store.jobs(1).single()
        assertEquals("profile:profile-1", job.key)
        assertEquals(JobState.FAILED, job.state)
        assertEquals("Access Denied", job.error)
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        assertEquals(2, uploader.ran.size)
    }

    private fun addJob(key: String, person: String, kind: JobKind, anchors: List<String>, state: JobState = JobState.PENDING) =
        UploadJob(treeId = 1, key = key, kind = kind, personGedcomId = person, familyGedcomId = "F3", anchors = anchors,
            params = listOf("first_name" to person), label = person, localHash = 0, state = state)

    @Test
    fun interruptedAddBecomesFailed() = runTest {
        store.saveJob(addJob("add:I6", "I6", JobKind.ADD_PARTNER, listOf("partner:I1"), JobState.SENDING))
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        val job = store.jobs(1).single()
        assertEquals(JobState.FAILED, job.state)
        assertTrue(job.error!!.contains("check on Geni"))
        assertEquals(emptyList<UploadJob>(), uploader.ran)
    }

    @Test
    fun addIsSendingWhileRunning() = runTest {
        store.saveJob(addJob("add:I6", "I6", JobKind.ADD_PARTNER, listOf("partner:I1")))
        var stateDuringRun: JobState? = null
        uploader.answer = { stateDuringRun = store.jobs(1).single().state; Outcome.Done }
        queue.runPending(1)
        assertEquals(JobState.SENDING, stateDuringRun)
    }

    @Test
    fun needsLoginPutsAddBackToPending() = runTest {
        store.saveJob(addJob("add:I6", "I6", JobKind.ADD_PARTNER, listOf("partner:I1")))
        uploader.answer = { Outcome.NeedsLogin }
        assertEquals(RunResult.NEEDS_LOGIN, queue.runPending(1))
        assertEquals(JobState.PENDING, store.jobs(1).single().state)
    }

    @Test
    fun laterSpouseRunsBeforeEarlierChild() = runTest {
        store.saveJob(addJob("add:I7", "I7", JobKind.ADD_CHILD, listOf("partner:I1", "partner:I6")))
        store.saveJob(addJob("add:I6", "I6", JobKind.ADD_PARTNER, listOf("child:I7", "partner:I1")))
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        assertEquals(listOf("add:I6", "add:I7"), uploader.ran.map { it.key })
    }

    @Test
    fun changedTargetUpdatesJob() = runTest {
        linkBoth()
        store.saveLinks(listOf(GeniLink(1, "union-1", "F1", true, null, "{\"id\":\"union-1\"}", 0)))
        val kid = LocalPerson("I3", null, "Kid", ProfileFields.EMPTY.copy(given = "Kid"))
        fun withKid(families: List<LocalFamily>) = tree().let { it.copy(people = it.people + kid, families = families) }
        queue.onSaved(1, withKid(listOf(LocalFamily("F1", "union-1", listOf("I1", "I2"), listOf("I3"), UnionFields.EMPTY))))
        queue.onSaved(1, withKid(listOf(LocalFamily("F1", "union-1", listOf("I1", "I2"), emptyList(), UnionFields.EMPTY),
            LocalFamily("F2", null, listOf("I3"), listOf("I1"), UnionFields.EMPTY))))
        val job = store.jobs(1).single()
        assertEquals(JobKind.ADD_PARTNER, job.kind)
        assertEquals("F2", job.familyGedcomId)
        assertEquals(1, job.version)
    }

    @Test
    fun revertDuringUploadIsPlannedAgain() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver))
        uploader.answer = { job ->
            if (uploader.ran.size == 1) queue.onSaved(1, tree(ivan)) // The user reverts to Engineer while Driver uploads
            uploader.geniApplies(job)
        }
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        assertEquals(listOf(listOf("occupation" to "Driver"), listOf("occupation" to "Engineer")), uploader.ran.map { it.params })
    }

    @Test
    fun valueGeniStoresDifferentlyFailsInsteadOfLooping() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver))
        uploader.answer = { Outcome.Done } // Geni keeps Engineer: the snapshot never matches
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        assertEquals(1, uploader.ran.size)
        val job = store.jobs(1).single()
        assertEquals(JobState.FAILED, job.state)
        assertTrue(job.error!!.contains("different value"))
    }
}
