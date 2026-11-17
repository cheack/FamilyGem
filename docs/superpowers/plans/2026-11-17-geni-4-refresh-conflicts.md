# Geni Refresh and Conflicts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Changes other people make on Geni reach the cache without wiping unsent local edits, local edits never overwrite changes made on Geni meanwhile, and the user resolves real conflicts field by field; merged and deleted profiles are followed.

**Architecture:** Every comparison works on field groups of the existing projections (`ProfileFields`, `UnionFields`): the groups are exactly the steps `GeniMapper.applyProfile`/`applyUnion` apply as a whole. A three-way check (snapshot, local, Geni) gives the groups changed on each side and the conflicts. Loads skip a record in conflict and keep its snapshot; uploads fetch Geni's version first, merge Geni's non-conflicting changes into the tree, and pause the job as `CONFLICT` otherwise. A resolver applies the user's choice per group and moves the snapshot forward. Background refresh reuses the prefetcher.

**Tech Stack:** Kotlin, Room (`:geni`, job state stored as text: no migration for a new state), OkHttp, Gson, coroutines, gedcom5-java; JUnit 4 with mockwebserver3.

**Spec:** `docs/superpowers/specs/2026-11-16-geni-sync-design.md` ("Changes made by others and conflicts", "Local edits and the upload queue").

## Global Constraints

- Geni is the source of truth; never silently overwrite changes other Geni users made (anti-goal).
- Never wipe local-only data: refresh touches only Geni-mapped field groups.
- Never upload data the user did not change.
- Upstream touched only through one-line `// GENI-HOOK` calls listed in `geni/UPSTREAM-HOOKS.md`.
- Rate limit: 40 requests per 10 s; the pre-upload check costs one GET per update job.
- Background work in-process (upstream `Notifier` cancels all WorkManager work on save).
- New code in Kotlin; no secrets in git; GPL-3.0.

## Review Focus

1. A load while an edit of the same field is still queued: the local value stays, the snapshot does not move, the upload stops as a conflict instead of overwriting Geni.
2. A load of a profile added without a snapshot after a local edit: the local fields stay, only the snapshot is set.
3. Geni changed another field than the user: both changes survive (Geni's in the tree, the user's on Geni), nothing is reverted on either side.
4. After an upload merged Geni's changes into the tree, the queue plans with the new tree, never with the tree captured before the merge (that would send Geni's old values back).
5. A profile deleted on Geni with nothing pending disappears from the cache; with a pending change it waits for the user.

---

## PR 1 (`geni-refresh-1`): three-way check on load and before upload — Tasks 1–4

### Task 1: Field groups and the three-way check

**Files:**
- Create: `geni/src/main/java/app/familygem/geni/map/FieldGroups.kt`
- Test: `geni/src/test/java/app/familygem/geni/map/FieldGroupsTest.kt`

**Interfaces:**
- Produces: `enum class ProfileGroup { NAMES, SEX, BIRTH, BAPTISM, DEATH, BURIAL, OCCUPATION, ABOUT }`, `enum class UnionGroup { MARRIAGE, DIVORCE }`;
  `fun ProfileFields.value(group: ProfileGroup): Any?`, `fun UnionFields.value(group: UnionGroup): Any?`;
  `data class ThreeWay<G>(val local: Set<G>, val geni: Set<G>, val conflicts: Set<G>)`;
  `fun threeWay(snapshot: ProfileFields, local: ProfileFields, geni: ProfileFields): ThreeWay<ProfileGroup>` and the same for `UnionFields`.
  Group values: NAMES = given, surname, maiden, suffix, nicknames; DEATH = death, dead, causeOfDeath; the others one field each. Conflict = changed on both sides to different values.

- [x] **Step 1: Write the failing tests** `localOnlyChangeIsNoConflict`, `geniOnlyChangeIsNoConflict`, `sameValueOnBothSidesIsNoConflict`, `differentValuesOnBothSidesConflict`, `deathGroupIncludesCauseAndFlag`, `unionDivorceConflict`.
- [x] **Step 2: Run** `:geni:testDebugUnitTest --tests '*FieldGroupsTest'` — Expected: FAIL (unresolved references).
- [x] **Step 3: Implement** in `FieldGroups.kt`.
- [x] **Step 4: Run** — Expected: PASS.
- [x] **Step 5: Commit** `Compare Geni fields by group: local, Geni and conflicting changes`.

### Task 2: Loads keep unsent local edits

**Files:**
- Modify: `geni/src/main/java/app/familygem/geni/sync/FamilyMerger.kt`, `geni/src/main/java/app/familygem/geni/map/GeniMapper.kt`
- Test: `geni/src/test/java/app/familygem/geni/sync/FamilyMergerTest.kt`

**Interfaces:**
- Consumes: Task 1.
- Produces: `GeniMapper.applyProfile(gedcom, person, profile, previous, keep: Set<ProfileGroup> = emptySet())`, `applyUnion(..., keep: Set<UnionGroup> = emptySet())` (kept groups are not applied).
  `FamilyMerger.merge` for a person linked with a snapshot: three-way of snapshot, `ProfileFields.of(person)`, Geni; with conflicts the person is not changed and the link keeps its old snapshot, `geniUpdatedAt` and verified hash; without conflicts as today (local-only groups are not touched because Geni did not change them).
  Linked without snapshot (added by the app, refresh failed): if `ProfileFields.of(person).hashCode() != link.verifiedHash` the person is not changed but the new snapshot is stored; otherwise everything is applied. Same rules for unions (marriage, divorce; partner and child links are always merged).

- [x] **Step 1: Write the failing tests** `loadKeepsQueuedEditOfSameField` (local occupation edited, Geni changed occupation too: person unchanged, link snapshot unchanged), `loadAppliesOtherFieldBesideLocalEdit` (local occupation edited, Geni changed birth: birth applied, occupation kept, snapshot new), `loadOfEditedPersonWithoutSnapshotKeepsLocal`, `loadOfUnchangedPersonWithoutSnapshotAppliesAll`, `loadKeepsQueuedMarriageEdit`.
- [x] **Step 2: Run** `--tests '*FamilyMergerTest'` — Expected: the new tests FAIL.
- [x] **Step 3: Implement.**
- [x] **Step 4: Run** all `:geni` unit tests — Expected: PASS.
- [x] **Step 5: Commit** `Never let a Geni load overwrite a queued local edit`; tick the roadmap's deferred item about loads without snapshot.

### Task 3: Check Geni before uploading an update

**Files:**
- Modify: `geni/src/main/java/app/familygem/geni/sync/Uploader.kt`, `UploadJob.kt` (state `CONFLICT`), `SyncQueue.kt`, `geni/src/main/java/app/familygem/geni/Geni.kt`
- Test: `UploaderTest.kt`, `SyncQueueTest.kt`

**Interfaces:**
- Consumes: Tasks 1–2.
- Produces: `JobState.CONFLICT`; `Outcome.Conflict(val groups: List<String>)`.
  `Uploader.update`: fetches the record; if its projection equals the snapshot's, sends as today. Otherwise three-way with the tree's record (read inside `host.edit`): conflicts → `Conflict` (nothing sent, tree and snapshot unchanged); none → applies Geni's groups to the tree (`previous` = snapshot), stores Geni's version as the snapshot (conditional), then sends the job's params and refreshes the snapshot.
  `Uploader.onTreeEdited: (Int, LocalTree) -> Unit` (default no-op), called with `LocalTree.capture` inside every `host.edit` of the uploader (merge and add tags); `SyncQueue.setLocal(treeId, local)` stores it as the last saved tree; `Geni.queue` wires the two.
  `SyncQueue`: `Conflict` saves the job as `CONFLICT` with the message `changed on Geni meanwhile: <groups>`; the planner keeps `CONFLICT` for the same change (like `FAILED`) and resets it to `PENDING` for a new one; `CONFLICT` never runs.

- [ ] **Step 1: Write the failing tests** `updateSendsWhenGeniUnchanged`, `updateStopsOnConflict` (no POST), `updateMergesOtherGeniChangeFirst` (tree gets Geni's birth, POST has only the job's params, the reported tree carries the merged birth), `conflictJobIsKeptUntilChangedAgain`, `queuePlansWithTreeReportedByUploader`.
- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** all `:geni` unit tests and `:app:assembleDebug` — Expected: PASS.
- [ ] **Step 5: Commit** `Check Geni before each update: merge others' changes, stop on conflicts`.

### Task 4: Open PR 1

- [ ] Instrumented `RoomSyncStoreTest` on the phone (job with state `CONFLICT` round-trips).
- [ ] Push `geni-refresh-1`, open the PR into `geni`, follow CodeRabbit, merge.

## PR 2 (`geni-refresh-2`): resolving conflicts — Tasks 5–7

### Task 5: Conflict details and resolution

**Files:**
- Create: `geni/src/main/java/app/familygem/geni/sync/ConflictResolver.kt`
- Test: `geni/src/test/java/app/familygem/geni/sync/ConflictResolverTest.kt`

**Interfaces:**
- Produces: `data class FieldConflict(val group: String, val mine: String, val geni: String)` (display texts: dates and places joined, empty as "—");
  `class ConflictResolver(client, store, host)`: `suspend fun details(job: UploadJob): List<FieldConflict>` (fetches Geni; reads the record inside `host.edit` without changing it); `suspend fun resolve(job: UploadJob, keepMine: Set<String>)`: fetches Geni, applies it to the record except the kept groups (`previous` = snapshot), stores Geni's version as the snapshot, sets the job `PENDING` (the next plan sends only the kept groups and other local edits).

- [ ] **Step 1: Write the failing tests** `detailsListOnlyConflictingGroups`, `keepMineSendsMineOnly`, `takeGeniAppliesGeniAndLeavesNothingToSend`.
- [ ] **Step 2–4:** run (FAIL), implement, run all `:geni` tests (PASS).
- [ ] **Step 5: Commit** `Resolve Geni conflicts field by field`.

### Task 6: Conflicts on the account screen

**Files:**
- Modify: `geni/src/main/java/app/familygem/geni/ui/GeniLoginActivity.kt`, `geni_activity_login.xml`, `values/geni_strings.xml`, `values-ru/geni_strings.xml`, `Geni.kt` (`resolver(context)`).

- [ ] **Step 1:** "Conflicts (N)" button; per conflict a dialog with the record label and, for each group, "Mine: …" / "On Geni: …" choices (default: Geni); "Apply" calls `resolve`, then `resumeUploads`; offline shows `geni_offline`.
- [ ] **Step 2:** `:app:assembleDebug`; check on the phone over adb: edit a field in the app and the same field on the sandbox through the API test helper (Task 9), see the conflict, resolve both ways.
- [ ] **Step 3: Commit** `Show Geni conflicts on the account screen`.

### Task 7: Open PR 2 — push `geni-refresh-2`, PR, CodeRabbit, merge.

## PR 3 (`geni-refresh-3`): background refresh, merged and deleted profiles — Tasks 8–10

### Task 8: Refresh cadence

**Files:**
- Modify: `geni/src/main/java/app/familygem/geni/sync/LoadPolicy.kt`, `app/src/geni/java/app/familygem/geni/GeniGlue.kt`, `GeniPrefetch.kt`
- Test: `LoadPolicyTest.kt`

**Interfaces:**
- Produces: `LoadPolicy.OPEN_REFRESH_MILLIS = 1 h` used when a card opens (was 1 day); daily refresh: at app start, once per 24 h (time kept in the tree row or app prefs) and only on an unmetered network, `GeniPrefetch.enqueue(tree, root, prefetchDepth)` with freshness of 24 h.

- [ ] Steps: failing `LoadPolicyTest` cases for both intervals → implement → PASS → commit `Refresh Geni trees daily on Wi-Fi and cards hourly`.

### Task 9: Merged and deleted profiles

**Files:**
- Modify: `GeniClient.kt` (profiles keyed by the requested ID too), `FamilyMerger.kt`, `GeniLoader.kt`, `TreeHost.kt` (`removePerson(gedcom, personId)` is done inside `edit`: unlink from families, delete; empty families removed), `AppTreeHost.kt`, `Uploader.kt`
- Test: `FamilyMergerTest.kt`, `GeniLoaderTest.kt`, `UploaderTest.kt`; sandbox exploration as an instrumented test in `app/src/androidTest` run with `am instrument` (never `connectedAndroidTest`, which uninstalls the app and its login).

**Interfaces:**
- Merged: a profile answered with `merged_into` (or with another `id` than asked) moves its link and tag to the surviving ID; the old link is deleted; jobs keyed by the old ID are re-keyed.
- Deleted (`deleted: true`): with nothing pending (three-way local set empty) the person is removed from the tree and the link deleted; with pending changes the job becomes `CONFLICT` with message `deleted on Geni` and the conflict dialog offers "Remove from device" / "Keep as local person" (tag and link removed).

- [ ] **Step 1:** explore on the sandbox: create two profiles, merge them, delete one; record the real responses in the spec.
- [ ] **Step 2–5:** failing tests `mergedProfileMovesLink`, `deletedUnchangedProfileIsRemoved`, `deletedEditedProfileWaitsForUser`, `uploadToDeletedProfileIsConflict` → implement → PASS → commit `Follow merged and deleted Geni profiles`.

### Task 10: Phone check and PR 3

- [ ] On the phone over adb: daily refresh log line, merged and deleted cases from Step 1 reach the app.
- [ ] Findings in the spec; stage 4 ticked in the roadmap; progress log lines for the merged PRs.
- [ ] Push `geni-refresh-3`, PR, CodeRabbit, merge.
