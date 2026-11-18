# Geni Upstream UI Rules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** FamilyGem features that would break or leak a Geni tree are hidden or blocked; deleting a person of a Geni tree follows explicit rules (remove from this device, or delete on Geni when the user manages the profile).

**Architecture:** Each upstream screen gets one `// GENI-HOOK` line calling a function of `GeniGlue` (app, `src/geni`), which asks a small in-memory index of Geni tree IDs (`GeniTrees`, `:geni`) and shows the Geni dialog or message. Local removal reuses `GoneProfiles.remove` (no upload: the planner already treats a missing record as "not uploaded"). Deleting on Geni is a new job kind `DELETE_PROFILE`, run by the existing queue.

**Tech Stack:** Kotlin, Room (job kind stored as text: no migration), OkHttp, coroutines; JUnit 4 with mockwebserver3.

**Spec:** `docs/superpowers/specs/2026-11-16-geni-sync-design.md` ("Delete and unlink", "FamilyGem features on a Geni tree", "Upstream hooks").

## Global Constraints

- Upstream touched only through one-line `// GENI-HOOK` calls, each listed in `geni/UPSTREAM-HOOKS.md`; no reformatting of upstream code.
- Never delete a profile on Geni without an explicit confirmation that names the consequence for the shared tree.
- Never send data of other Geni users to a third-party server (sharing through the FamilyGem server stays hidden for Geni trees).
- GEDCOM export stays allowed.
- New code in Kotlin; no secrets in git; GPL-3.0.

## Review Focus

1. Deleting the root person (the user's own profile): blocked with an explanation, in every delete path.
2. Deleting a person that exists only locally (offline add not uploaded yet): ordinary upstream delete, its pending add job is dropped, nothing reaches Geni.
3. Delete on Geni fails (403, offline): the job is kept and shown in the failed list; the person stays removed locally only after Geni answers `Deleted`.
4. A delete job whose person is already gone from the tree is not dropped by the planner's "record gone" rule.
5. A backup of a Geni tree restored on the device is a plain local tree: never uploads, never loaded, no Geni menu.

## Findings that shape the plan

- Backup restore (`TreeUtil.unzipTree`) always creates a new tree ID (`Global.settings.max() + 1`), and Geni rows are keyed by tree ID. A restored backup is therefore a plain local tree and no snapshot reset is needed. Task 5 only verifies this and documents it.
- Local delete of a linked person already uploads nothing: the planner marks the link orphaned and drops its jobs. "Remove from this device" is therefore the existing behaviour made explicit, plus a fix that the next load must not bring the person back unless the user opens them.
- Delete sites calling `person.delete()`: `ProfileActivity.kt` (menu id 5), `PersonsFragment.kt` (menu id 6), `DiagramFragment.kt` (menu id 7), `RelativesFragment.kt` (menu id 316). Unlink sites: `RelativesFragment.kt` (`unlinkPerson`), `DiagramFragment.kt` (two `unlinkPerson` calls), `DetailActivity.java` (`unlinkRefs`).
- Tree menu ids: 5 share, 6 merge, 7 compare (`TreesActivity.kt`, popup built around line 165).

---

## PR 1 (`geni-ui-1`): hide features, block unlink — Tasks 1–2

### Task 1: Hide sharing, merge and compare for Geni trees

**Files:**
- Create: `geni/src/main/java/app/familygem/geni/GeniTrees.kt`
- Modify: `geni/src/main/java/app/familygem/geni/GeniHooks.kt`, `app/src/geni/java/app/familygem/geni/GeniGlue.kt`, `app/src/main/java/app/familygem/TreesActivity.kt` (one line after `popup.menu` is filled, before `popup.show()`), `geni/UPSTREAM-HOOKS.md`
- Test: `geni/src/test/java/app/familygem/geni/GeniTreesTest.kt`

**Interfaces:**
- Produces: `object GeniTrees { fun isGeni(treeId: Int): Boolean; suspend fun load(store: SyncStore); fun add(treeId: Int); fun remove(treeId: Int) }` (thread-safe set; `isGeni` never touches Room).
- Produces: `GeniHooks.trimTreeMenu(treeId: Int, menu: Menu)` removing item ids 5, 6, 7 when `GeniTrees.isGeni(treeId)`.
- Produces: `GeniGlue.trimTreeMenu(treeId: Int, menu: Menu)` (calls `install()` then the hook function).

- [ ] **Step 1: Write failing tests in `GeniTreesTest`**: `isGeni` is false before `load`; after `load(store)` with trees 3 and 5 saved in `InMemorySyncStore`, `isGeni(3)` and `isGeni(5)` are true and `isGeni(4)` false; `add(9)` makes `isGeni(9)` true; `remove(3)` makes it false.
- [ ] **Step 2: Run** `gradle :geni:testDebugUnitTest --tests '*GeniTreesTest*'`; expected FAIL (class missing).
- [ ] **Step 3: Implement `GeniTrees`** with a `ConcurrentHashMap.newKeySet<Int>()`.
- [ ] **Step 4: Keep the set current.** `GeniGlue.install()` launches `GeniTrees.load(Geni.store(...))` once; the code that creates a Geni tree calls `GeniTrees.add`; `GeniGlue.onTreeDeleted` calls `GeniTrees.remove`. Find the creation point with `git grep -n saveTree geni/src/main`.
- [ ] **Step 5: Implement `GeniHooks.trimTreeMenu`** (`menu.removeItem(5/6/7)`), `GeniGlue.trimTreeMenu`, and the hook line `app.familygem.geni.GeniGlue.trimTreeMenu(treeId, menu) // GENI-HOOK` in `TreesActivity.kt` right before `popup.setOnMenuItemClickListener(MenuItemClickListener(position, treeId))`. Add the row to `geni/UPSTREAM-HOOKS.md`.
- [ ] **Step 6: Run the unit tests** (same command, whole module `:geni:testDebugUnitTest`); expected PASS.
- [ ] **Step 7: Build** `gradle :app:assembleDebug`; expected BUILD SUCCESSFUL. Phone check: the Geni tree's three-dots menu has no Share, Merge, Compare; a local tree's menu is unchanged; Export GEDCOM is still there (expert mode).
- [ ] **Step 8: Commit.**

### Task 2: Block unlinking a relationship in Geni trees

**Files:**
- Modify: `geni/src/main/java/app/familygem/geni/GeniHooks.kt`, `app/src/geni/java/app/familygem/geni/GeniGlue.kt`, `geni/src/main/res/values/strings.xml`, `RelativesFragment.kt` (before `FamilyUtil.unlinkPerson(selected, family)`), `DiagramFragment.kt` (before each of the two `unlinkPerson` calls), `DetailActivity.java` (before `unlinkRefs`), `geni/UPSTREAM-HOOKS.md`
- Test: `geni/src/test/java/app/familygem/geni/GeniTreesTest.kt` (message choice only; the dialog is checked on the phone)

**Interfaces:**
- Consumes: `GeniTrees.isGeni(treeId: Int): Boolean`.
- Produces: `GeniGlue.blockUnlink(context: Context): Boolean`: true when `Global.settings.openTree` is a Geni tree; it has then shown a dialog (`geni_unlink_blocked`: "Geni cannot remove a relationship through its API. Change it on geni.com; it will reach this tree on the next refresh.") and the caller must return without unlinking.

- [ ] **Step 1: Implement `GeniGlue.blockUnlink`** as above (string in `geni` module resources, default language only; upstream translations untouched).
- [ ] **Step 2: Add one hook line at each call site**, shape `if (app.familygem.geni.GeniGlue.blockUnlink(context)) return // GENI-HOOK` (use `return@...` or the local equivalent where the unlink sits inside a lambda; read the surrounding 10 lines first). Add rows to `UPSTREAM-HOOKS.md`.
- [ ] **Step 3: Build and phone-check:** in the Geni tree, "Unlink" of a spouse or child in the profile Family tab and in the diagram shows the dialog and changes nothing; in a local tree unlink works as before.
- [ ] **Step 4: Commit; open PR 1 into `geni`.**

---

## PR 2 (`geni-ui-2`): delete rules — Tasks 3–4

### Task 3: Delete on Geni as an upload job

**Files:**
- Modify: `geni/src/main/java/app/familygem/geni/sync/UploadJob.kt` (`JobKind.DELETE_PROFILE`), `sync/UploadPlanner.kt` (do not drop a delete job as "record gone"), `sync/Uploader.kt` (`run` branch), `sync/SyncQueue.kt` (public `suspend fun enqueueDelete(treeId: Int, geniId: String, label: String)`), `api/GeniClient.kt` (managed-profile check from Task 3 Step 1)
- Test: `geni/src/test/java/app/familygem/geni/sync/UploaderTest.kt`, `UploadPlannerTest.kt`, `SyncQueueTest.kt`, `api/GeniClientTest.kt`

**Interfaces:**
- Consumes: `UploadJob(key = "delete:<geniId>", kind = JobKind.DELETE_PROFILE, geniId = <id>, params = emptyList(), label, localHash = 0)`.
- Produces: `GeniClient.managedProfileIds(): Set<String>` (`GET user/managed-profiles`, all pages) and `suspend fun SyncQueue.enqueueDelete(...)`.

- [ ] **Step 1: Sandbox exploration (needs login; do it over the dev tool `GeniSandboxTool`).** Find out which call says whether the user manages a profile: `GET user/managed-profiles` paging and ID format, and whether the `profile` response has a `managers`/`managed` field. Record the real answer in the design doc's "Found on the sandbox" list. If `managed-profiles` works, use it; if not, fall back to trying the delete and showing the 4xx as a failed job.
- [ ] **Step 2: Write failing tests.** `GeniClientTest`: `managedProfileIds()` over two pages returns the union of IDs (response shape from Step 1). `UploaderTest`: a `DELETE_PROFILE` job posts `profile-X/delete` and on `{"result":"Deleted"}` returns done and deletes the link of `X`; a 403 body gives a permanent failure with the Geni message; an `UnknownHostException` is retried. `UploadPlannerTest`: a `delete:X` job survives planning when no record is linked to `X` and no GEDCOM record exists for it.
- [ ] **Step 3: Run the new tests; expected FAIL.**
- [ ] **Step 4: Implement** the enum value, the `Uploader.run` branch (reuse the existing outcome mapping; after success `deleteLink`), the planner exception (`job.kind == JobKind.DELETE_PROFILE` is never `gone`), `SyncQueue.enqueueDelete` (upsert by key, wake the runner like other enqueues) and `GeniClient.managedProfileIds`.
- [ ] **Step 5: Run `:geni:testDebugUnitTest`; expected PASS.**
- [ ] **Step 6: Commit.**

### Task 4: Delete dialog for people of Geni trees

**Files:**
- Modify: `app/src/geni/java/app/familygem/geni/GeniGlue.kt`, `geni/src/main/java/app/familygem/geni/GeniHooks.kt`, `geni/src/main/res/values/strings.xml`, `ProfileActivity.kt` (menu id 5), `PersonsFragment.kt` (id 6), `DiagramFragment.kt` (id 7), `RelativesFragment.kt` (id 316), `geni/UPSTREAM-HOOKS.md`
- Test: `geni/src/test/java/app/familygem/geni/sync/DeleteRulesTest.kt`

**Interfaces:**
- Consumes: `GeniTrees.isGeni`, `GeniMapper.getGeniId(person): String?`, `GoneProfiles.remove(gedcom, personId): List<String>`, `SyncQueue.enqueueDelete`, `GeniClient.managedProfileIds()`, `SyncStore.link`.
- Produces: `object DeleteRules { fun decide(isGeniTree: Boolean, geniId: String?, rootGeniId: String): DeleteDecision }` with `sealed interface DeleteDecision { Upstream; BlockedRoot; Dialog }`: `Upstream` when the tree is not a Geni tree or the person has no Geni ID (local-only person: upstream deletes it and the planner drops its add job); `BlockedRoot` when `geniId == rootGeniId`; else `Dialog`.
- Produces: `GeniGlue.handleDelete(context: Context, person: Person, onDone: () -> Unit): Boolean`: true when Geni handled it (caller returns), false when upstream goes on.

- [ ] **Step 1: Write failing `DeleteRulesTest`**: non-Geni tree → `Upstream`; Geni tree and person without ID → `Upstream`; ID equal to root → `BlockedRoot`; other ID → `Dialog`.
- [ ] **Step 2: Run it; expected FAIL. Implement `DeleteRules`; run; expected PASS.**
- [ ] **Step 3: Implement `GeniGlue.handleDelete`.** `BlockedRoot`: dialog "This is your own Geni profile; it cannot be removed from the tree." `Dialog`: "Remove from this device" (always; `GoneProfiles.remove` on `Global.gc`, `TreeUtil.save`, then `onDone`; nothing is sent to Geni) and "Delete on Geni" shown only when the Geni ID is in `managedProfileIds()` (fetched when the dialog opens; offline or failure hides the button and says why). "Delete on Geni" opens a second confirmation: "Deletes this profile on Geni for everyone who shares this tree (relatives and other family members see it gone). This cannot be undone here." Confirming calls `enqueueDelete`, then removes the person locally exactly like the first choice, then `onDone`. Cancel does nothing.
- [ ] **Step 4: Add one hook line before each upstream delete site**, shape `if (app.familygem.geni.GeniGlue.handleDelete(context, person) { <site's own refresh> }) return // GENI-HOOK`; read each site first and keep the site's own refresh as the `onDone` argument. Rows in `UPSTREAM-HOOKS.md`.
- [ ] **Step 5: Next load must not bring a removed person back by itself:** check `GeniLoader` with a link marked orphaned (existing field) for a neighbour's load that lists the removed profile; add a test in `GeniLoaderTest` that such a profile is not re-added unless loaded by its own ID. If the loader already behaves so, the test only pins it.
- [ ] **Step 6: Phone check in the sandbox tree:** (a) root person: blocked; (b) a person the user manages: both choices shown, "Delete on Geni" removes the profile on sandbox.geni.com; (c) a person the user does not manage: only "Remove from this device", and it is not re-added by opening a neighbour; (d) airplane mode: delete on Geni waits in the queue and runs after the network is back; (e) a person added offline and deleted before upload: upstream delete, no job.
- [ ] **Step 7: Run `:geni:testDebugUnitTest` and `:app:assembleDebug`; commit; open PR 2 into `geni`.**

---

## PR 3 (`geni-ui-3`): backup check and close of the stage — Task 5

### Task 5: Backup restore and export stay safe

**Files:**
- Modify: `docs/superpowers/specs/2026-11-16-geni-sync-design.md` ("FamilyGem features on a Geni tree": restore text), `docs/superpowers/plans/README.md`
- Test: manual (phone)

- [ ] **Step 1: Phone check:** make a ZIP backup of the Geni tree, restore it from the Backup screen. Expected: a new tree entry (new ID) opens; its menu has the ordinary local items; editing a person there uploads nothing (`logcat | grep GeniSync` shows no planning); the Geni account screen lists no jobs for it. Export GEDCOM of the Geni tree still works.
- [ ] **Step 2: Fix the design text:** replace "snapshots are reset ... pending jobs are shown" by "a restored backup is a new local tree (new tree ID); Geni rows are keyed by tree ID, so it never uploads or loads; the `_GENI_ID` tags stay in its GEDCOM".
- [ ] **Step 3: Tick the stage 5 items in the roadmap, add the progress-log line for each merged PR, update memory.**
- [ ] **Step 4: Commit; PR 3 only if Steps 1–3 changed files beyond the docs; else commit the docs into PR 2's follow-up branch.**
