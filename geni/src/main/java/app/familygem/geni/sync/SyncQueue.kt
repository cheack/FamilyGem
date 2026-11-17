package app.familygem.geni.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RunResult {
    /** No pending job left. */
    EMPTY,
    /** A temporary problem stopped the queue: run again later. */
    RETRY_LATER,
    /** The token is missing or expired: run again after login. */
    NEEDS_LOGIN
}

/** Message of an add that was being sent when the app stopped: Geni may or may not have created the profile. */
private const val INTERRUPTED_ADD = "the app stopped while adding; check on Geni whether the person was created, then retry or dismiss"

private const val DIFFERENT_VALUE = "sent, but Geni stored a different value; check the profile on Geni"

/**
 * Upload queue of the Geni trees: turns saved trees into jobs and runs them in order.
 * One lock per tree guards the jobs; it is never held during network calls.
 * [runPending] must not run twice at the same time for one tree (the app runner guarantees it).
 */
class SyncQueue(
    private val store: SyncStore,
    private val uploader: Uploader,
    /** Told about every finished upload attempt (for the log); never given params or tokens. */
    private val onResult: (UploadJob, Outcome) -> Unit = { _, _ -> }
) {

    private val locks = mutableMapOf<Int, Mutex>()
    /** Last saved state of each tree, to plan again after an upload changed links or snapshots. */
    private val lastLocal = mutableMapOf<Int, LocalTree>()
    /** Key of the job being uploaded in each tree. */
    private val running = mutableMapOf<Int, String>()

    private fun lock(treeId: Int) = synchronized(locks) { locks.getOrPut(treeId) { Mutex() } }

    /** Plans the jobs for a saved tree and merges them into the queue by key. */
    suspend fun onSaved(treeId: Int, local: LocalTree) = lock(treeId).withLock {
        synchronized(lastLocal) { lastLocal[treeId] = local }
        plan(treeId, local)
    }

    /** Called with the tree lock held. */
    private suspend fun plan(treeId: Int, local: LocalTree) {
        val existing = store.jobs(treeId).associateBy { it.key }
        val plan = UploadPlanner.plan(treeId, local, store.links(treeId), existing.values.toList())
        for (wanted in plan.upserts) {
            val job = existing[wanted.key]
            when {
                job == null -> store.saveJob(wanted)
                job.sameChange(wanted) -> Unit // Keep it as it is, also when failed, dismissed or being sent
                // An add that was sent, failed or dismissed may exist on Geni: new details, but only the user sends it again
                job.isAdd() && job.state != JobState.PENDING ->
                    store.saveJob(wanted.copy(id = job.id, state = job.state, error = job.error, version = job.version + 1))
                else -> store.saveJob(wanted.copy(id = job.id, state = JobState.PENDING, error = null, version = job.version + 1))
            }
        }
        // The job being uploaded is neither deleted nor verified: its upload may change Geni after this plan
        val runningKey = synchronized(running) { running[treeId] }
        plan.deleteKeys.filter { it != runningKey }.mapNotNull { existing[it] }.forEach { store.deleteJob(it.id) }
        plan.staleLinks.forEach { store.deleteLink(treeId, it) }
        plan.orphaned.forEach { store.setOrphaned(treeId, it, true) }
        plan.unorphaned.forEach { store.setOrphaned(treeId, it, false) }
        saveVerified(plan.verified.filter { runningKey != "profile:${it.geniId}" && runningKey != "union:${it.geniId}" })
    }

    private fun UploadJob.sameChange(other: UploadJob) = params == other.params && kind == other.kind && geniId == other.geniId &&
            personGedcomId == other.personGedcomId && familyGedcomId == other.familyGedcomId && anchors == other.anchors

    /** Stores verified hashes, but only on links whose snapshot is still the one the hash was checked against. */
    internal suspend fun saveVerified(verified: List<GeniLink>) = verified.forEach {
        store.setVerifiedHash(it.treeId, it.geniId, it.gedcomId, it.snapshot, it.verifiedHash ?: return@forEach)
    }

    /** Runs the pending jobs of a tree in order until none is left or one has to wait. */
    suspend fun runPending(treeId: Int): RunResult {
        lock(treeId).withLock {
            // An add left "sending" by a stopped app may have reached Geni: the user decides, never an automatic retry
            store.jobs(treeId).filter { it.state == JobState.SENDING }
                .forEach { store.saveJob(it.copy(state = JobState.FAILED, error = INTERRUPTED_ADD)) }
        }
        val sent = mutableMapOf<String, List<Pair<String, String>>>() // Changes uploaded in this run, by key
        while (true) {
            val job = lock(treeId).withLock {
                var next = nextJob(store.jobs(treeId)) ?: return@withLock null
                // The same change planned again right after it was sent: Geni stored another value. Stop instead of looping
                while (sent[next.key] == next.params) {
                    store.saveJob(next.copy(state = JobState.FAILED, error = DIFFERENT_VALUE))
                    next = nextJob(store.jobs(treeId)) ?: return@withLock null
                }
                synchronized(running) { running[treeId] = next.key }
                if (next.isAdd()) next.copy(state = JobState.SENDING).also { store.saveJob(it) } else next
            } ?: return RunResult.EMPTY
            val outcome = try {
                uploader.run(job)
            } finally {
                synchronized(running) { running.remove(treeId) }
            }
            onResult(job, outcome)
            lock(treeId).withLock {
                val current = store.jobs(treeId).firstOrNull { it.id == job.id }
                val unchanged = current?.version == job.version
                when (outcome) {
                    Outcome.Done -> {
                        sent[job.key] = job.params
                        // An added person is linked now: newer details of it go as an update. A newer version
                        // of an update planned during the upload stays pending and runs next
                        if (!job.isAdd()) store.deleteJobIfVersion(job.id, job.version)
                        // Links and snapshots changed. The saved tree was captured before the add tagged the person
                        synchronized(lastLocal) { lastLocal[treeId] }?.let { local ->
                            val current = if (job.isAdd()) withAddedTags(treeId, local, job).also { tagged ->
                                synchronized(lastLocal) { lastLocal[treeId] = tagged }
                            } else local
                            plan(treeId, current)
                        }
                        // Only now, with an update of newer details stored: an add left by a stopped app finds its link and is not sent
                        if (job.isAdd()) store.deleteJob(job.id)
                    }
                    // A failed add may exist on Geni: recorded whatever its version, so it is never sent again by itself
                    is Outcome.Failed -> if (current != null && (unchanged || job.isAdd()))
                        store.saveJob(current.copy(state = JobState.FAILED, error = outcome.message))
                    is Outcome.Retry, Outcome.NeedsLogin ->
                        if (current?.state == JobState.SENDING) store.saveJob(current.copy(state = JobState.PENDING)) // Not sent
                }
            }
            when (outcome) {
                is Outcome.Retry -> return RunResult.RETRY_LATER
                Outcome.NeedsLogin -> return RunResult.NEEDS_LOGIN
                else -> Unit
            }
        }
    }

    /** The saved tree with the tags an add gave to its person and family, as the live tree has them now. */
    private suspend fun withAddedTags(treeId: Int, local: LocalTree, job: UploadJob): LocalTree {
        val person = job.personGedcomId?.let { store.linkByGedcomId(treeId, it) }?.geniId?.takeIf { it.startsWith("profile-") }
        val family = job.familyGedcomId?.let { store.linkByGedcomId(treeId, it) }?.geniId?.takeIf { it.startsWith("union-") }
        return local.copy(
            people = local.people.map { if (it.gedcomId == job.personGedcomId && it.geniId == null && person != null) it.copy(geniId = person) else it },
            families = local.families.map { if (it.gedcomId == job.familyGedcomId && it.geniId == null && family != null) it.copy(geniId = family) else it }
        )
    }

    private fun UploadJob.isAdd() = kind == JobKind.ADD_CHILD || kind == JobKind.ADD_PARTNER

    /**
     * First pending job, in order, that does not wait for another add, whatever order they were planned in:
     * a child waits for every parent still to be added (it must go under the couple's union);
     * a partner waits only while none of its relatives is on Geni or added yet.
     */
    private fun nextJob(jobs: List<UploadJob>): UploadJob? {
        val pending = jobs.filter { it.state == JobState.PENDING }
        val adding = jobs.filter { it.isAdd() && (it.state == JobState.PENDING || it.state == JobState.SENDING) }
            .mapNotNull { it.personGedcomId }.toSet()
        fun waits(job: UploadJob): Boolean {
            val waiting = job.anchors.filter { it.substringAfter(':') in adding }
            return when (job.kind) {
                JobKind.ADD_CHILD -> waiting.any { it.startsWith("partner:") }
                JobKind.ADD_PARTNER -> job.anchors.isNotEmpty() && waiting.size == job.anchors.size
                else -> false
            }
        }
        return pending.firstOrNull { !waits(it) } ?: pending.firstOrNull()
    }

    /**
     * Puts failed jobs back in the queue: only those with these IDs, the ones the user saw
     * (a job that failed meanwhile, maybe an add, is never retried unseen).
     */
    suspend fun retryFailed(ids: Set<Long>) = changeFailed(ids) { it.copy(state = JobState.PENDING, error = null) }

    /** Hides the failed jobs with these IDs; they stay stored so the same change is not planned again. */
    suspend fun dismissFailed(ids: Set<Long>) = changeFailed(ids) { it.copy(state = JobState.DISMISSED) }

    private suspend fun changeFailed(ids: Set<Long>, change: (UploadJob) -> UploadJob) {
        val failed = { job: UploadJob -> job.state == JobState.FAILED && job.id in ids }
        for (treeId in store.allJobs().filter(failed).map { it.treeId }.distinct()) {
            lock(treeId).withLock {
                store.jobs(treeId).filter(failed).forEach { store.saveJob(change(it)) }
            }
        }
    }
}
