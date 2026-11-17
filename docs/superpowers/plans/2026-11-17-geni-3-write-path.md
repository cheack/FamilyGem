# Geni Sync, Plan 3: Write Path — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Edits made in a Geni tree with FamilyGem's normal editors reach Geni: changed profile fields, changed marriage and divorce, and new parents, partners and children of Geni profiles, also when made offline.

**Architecture:** One `// GENI-HOOK` line at the end of `TreeUtil.saveJson` hands the saved tree to `:geni`, which captures a small projection of every person and family (`LocalTree`, only the fields Geni has). A pure `UploadPlanner` compares each projection with the projection of the record's Geni snapshot and reconciles a job queue in Room (one job per record, keyed). A `SyncQueue` runs the jobs in order through `Uploader` (form POSTs to Geni), in an in-process coroutine scope with back-off, and records permanent failures for the user. Projections are compared in GEDCOM form on both sides (the snapshot goes through the same `GeniMapper` as loads), so a value that round-trips unchanged never produces a job.

**Tech Stack:** Kotlin, gedcom5-java 1.15.0, Room 2.8.5 (KSP), OkHttp 5.5.0 + mockwebserver3, Gson, kotlinx-coroutines 1.11.0, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-16-geni-sync-design.md` (sections "Local edits and the upload queue", "Mapping GEDCOM to Geni"). Builds on plans 1 and 2.

## Global Constraints

- Upstream files are touched only by one-line `// GENI-HOOK` lines listed in `geni/UPSTREAM-HOOKS.md`. This plan adds exactly one: the last line of `TreeUtil.saveJson`.
- Upload jobs come only from what was actually saved (`saveJson`), never from `TreeUtil.save` or from unsaved edits.
- Data Geni cannot store (sources, notes, media, other facts, extra names, dates Geni cannot express) stays local and is never uploaded; it must not cause a job either.
- Never upload a field the user did not change: a job carries only the changed field groups.
- Deletions are not uploaded in this plan (stage 5 adds delete rules with confirmation). A person deleted locally only drops its pending jobs.
- Linking two people who are both already on Geni is not uploaded (the API has no call for it); such a family change stays local.
- Background work runs in an in-process coroutine scope, not WorkManager: upstream `Notifier` calls `WorkManager.cancelAllWork()` on every tree save.
- All requests go through the shared `RateLimiter`; no secrets in logs.
- New strings in `geni/src/main/res/values/geni_strings.xml` and `values-ru/geni_strings.xml`, prefix `geni_`.
- Build and test: `JAVA_HOME=/home/vp/jdk $GRADLE ...` with `$GRADLE = ~/.gradle/wrapper/dists/gradle-9.9.0-bin/*/gradle-9.9.0/bin/gradle`, in an rsync copy of the repo in the scratchpad (the IDE's Gradle daemons corrupt `app/build`).
- Three PRs into `geni`, each from its own branch off the updated `geni` and reviewed by CodeRabbit: `geni-write-1` (Tasks 1–4), `geni-write-2` (Tasks 5–7), `geni-write-3` (Tasks 8–10). Commit, push and PR only after the user says yes.

Geni facts this plan relies on (sandbox, 2026-11-17, all with form-encoded `POST`):
- `profile-X/update` changes only the parameters sent; an empty value clears a field (`occupation=`, `about_me=`, `birth[location][city]=`).
- A date is replaced as a whole: sending only `birth[date][year]=1992` drops the day and month. Always send `day`, `month`, `year` and `circa` together (empty strings for missing parts). Empty `day`, `month`, `year` and no location remove the event. `birth=` returns HTTP 500.
- `birth[location][place_name]=...` keeps the old `city`, `country` etc. unless they are sent empty.
- Death is ignored while the profile is alive: send `is_alive=false` in the same request as `death[...]`.
- `is_alive=true` alone clears the death date, place and `cause_of_death`.
- `nicknames=A, B` becomes `["A", "B"]`; `gender=unknown` clears the gender.
- `profile-X/add-child`, `add-parent`, `add-partner`, `add-sibling` and `union-U/add-child`, `union-U/add-partner` return the new profile with `unions` = the one union it belongs to (the parents' union for a child, the new couple union for a partner or parent). `profile-X/add-child` and `add-parent` create a new union when needed.
- Profiles created without `is_alive=true` are deceased.
- `union-U/update` with `marriage[...]` and `divorce[...]` works; a divorce date sets `status` to `ex_spouse`. Changing `status` (for example to `ex_partner`) wipes marriage and divorce: never send `status`. An invalid parameter returns 400 after applying the valid ones.
- Write responses lack fields like `about_me`; fetch the record with `profiles()`/`unions()` after a write to get a full snapshot.
- `profile-X/delete` → `{"result":"Deleted"}`; deleting again → 403. Not used in this plan.

## Review Focus

- A value Geni stores in another form than the user typed (nicknames "A,B", lower-case month "5 jul 1991", extra spaces) must not be uploaded again on every save. Projections are normalized on both sides; test in Task 2 (`normalizedValuesCompareEqual`) and Task 5 (`uploadedChangeIsNotPlannedAgain`).
- A date Geni cannot express (`BEF 1900`, `BET 1900 AND 1910`, free text) must neither be uploaded nor clear Geni's date, and must not produce a job. Test in Task 3 (`unparseableDateKeepsGeniDate`) and Task 5 (`unparseableDateMakesNoJob`).
- A person added offline with a child added under them before any upload: the parent is created first and the child goes under the parent's new union. Test in Task 5 (`chainOfNewPeopleIsOrdered`) and Task 6 (`childOfNewPersonUsesTheNewUnion`).
- Editing a record while its job is uploading: the newer edit is not lost and is uploaded next. Test in Task 7 (`editDuringUploadKeepsNewerVersion`).
- No network, Geni 5xx or an expired token: jobs stay pending, are retried later or after login, nothing is marked failed. Test in Task 7 (`transientErrorKeepsJobPending`, `authErrorStopsTheQueue`).

---

### Task 1: POST requests in `GeniClient`

**Files:**
- Modify: `geni/src/main/java/app/familygem/geni/api/GeniClient.kt`
- Test: `geni/src/test/java/app/familygem/geni/api/GeniClientTest.kt`

**Interfaces:**
- Produces: `suspend fun GeniClient.post(path: String, params: List<Pair<String, String>>): String` (raw JSON body). Same token, 401, 429 and rate-limit handling as `getJson`; both go through one private `execute(request: Request): String`.

- [x] **Step 1: Write the failing tests** — `postSendsFormWithBearerToken` (MockWebServer records method `POST`, header `Authorization: Bearer t`, body `first_name=Ivan&birth%5Bdate%5D%5Byear%5D=1960`, path `/api/profile-1/update`), `postReturnsBody`, `post401ClearsToken` (throws `GeniAuthRequiredException`, token store empty), `post429RetriesOnce`, `post400ThrowsApiException` (code 400, message is the body).
- [x] **Step 2: Run** `$GRADLE :geni:testDebugUnitTest --tests '*GeniClientTest*'` — new tests FAIL (no `post`).
- [x] **Step 3: Implement** `post` with `okhttp3.FormBody`; move the loop of `getJson` into `execute`.
- [x] **Step 4: Run** the same command — all `GeniClientTest` tests PASS.
- [x] **Step 5: Commit** `Add POST requests to the Geni client`.

### Task 2: Projections of people and families (`LocalTree`)

**Files:**
- Create: `geni/src/main/java/app/familygem/geni/map/Fields.kt`
- Create: `geni/src/main/java/app/familygem/geni/map/GeniParams.kt` (only `parseDate` here; Task 3 adds the rest)
- Create: `geni/src/main/java/app/familygem/geni/sync/LocalTree.kt`
- Test: `geni/src/test/java/app/familygem/geni/map/FieldsTest.kt`, `geni/src/test/java/app/familygem/geni/map/GeniParamsTest.kt`, `geni/src/test/java/app/familygem/geni/sync/LocalTreeTest.kt`

**Interfaces:**
- Produces:
  - `object GeniParams { fun parseDate(text: String?): GeniDate? }`: optional prefix `ABT`, `EST` or `CAL` (→ `circa = true`), then `[day] [month] year` with English month abbreviations, any case; `year` 1–4 digits. Anything else (`BEF`, `AFT`, `BET … AND`, `FROM … TO`, text, day without month) → null.
  - `data class EventFields(val date: String?, val place: String?)`
  - `data class ProfileFields(val given: String?, val surname: String?, val maiden: String?, val suffix: String?, val nicknames: String?, val sex: String?, val birth: EventFields?, val baptism: EventFields?, val death: EventFields?, val burial: EventFields?, val dead: Boolean, val causeOfDeath: String?, val occupation: String?, val about: String?)` with `companion object { val EMPTY: ProfileFields; fun of(person: Person): ProfileFields; fun ofSnapshot(json: String): ProfileFields? }`
  - `data class UnionFields(val marriage: EventFields?, val divorce: EventFields?)` with `companion object { val EMPTY; fun of(family: Family): UnionFields; fun ofSnapshot(json: String): UnionFields? }`
  - `data class LocalPerson(val gedcomId: String, val geniId: String?, val label: String, val fields: ProfileFields)`
  - `data class LocalFamily(val gedcomId: String, val geniId: String?, val partners: List<String>, val children: List<String>, val fields: UnionFields)`
  - `data class LocalTree(val people: List<LocalPerson>, val families: List<LocalFamily>)` with `companion object { fun capture(gedcom: Gedcom): LocalTree }`

Rules (they mirror `GeniMapper`, which writes the same fields):
- `of(person)` reads what `GeniMapper.applyProfile` writes: the first name without `TYPE birth` ("given /surname/" split into `given` and `surname`, `NSFX`, `NICK`); the surname of the first `TYPE birth` name → `maiden`; first `SEX` (`M`/`F`, else null); first `BIRT`, `BAPM`, `DEAT`, `BURI` → `EventFields`; `dead` = a `DEAT` fact exists; `CAUS` of `DEAT`; first `OCCU` value; `_GENI_ABOUT` tag.
- Normalize every string: trim, collapse inner whitespace, blank → null. `nicknames`: split on commas, trim, drop blanks, join with ", ". Event `date`: if `GeniParams.parseDate` parses it, store the canonical form from `GeniMapper.gedcomDate`, otherwise the trimmed text. An event with neither date nor place → null (a `DEAT Y` gives `death = null`, `dead = true`).
- `ofSnapshot(json)`: parse `GeniProfile` (or `GeniUnion`), apply it with `GeniMapper.applyProfile`/`applyUnion` (previous = null) to a new `Person`/`Family` in a scratch `Gedcom`, then `of(...)`. Returns null on bad JSON.
- `LocalPerson.label`: display name (`given surname`), else the GEDCOM ID. `LocalFamily.partners`: husband refs then wife refs.

- [x] **Step 1: Write the failing tests** — `parsesDates` (`"12 MAR 1960"` → 12/3/1960, `"jun 2012"` → –/6/2012, `"ABT 1960"` circa, `"BEF 1900"` → null, `"5 1990"` → null), `personFieldsMatchMapper` (a `GeniProfile` fixture from `geni/src/test/resources/geni/` applied with `GeniMapper` then `of()` gives the same `ProfileFields` as `ofSnapshot()` of its raw JSON), `normalizedValuesCompareEqual` (person with `NICK "A,B"`, `DATE "5 jul 1991"`, name `"Ivan  /P/ "` equals the projection of a snapshot with `nicknames ["A","B"]`, date `{day 5, month 7, year 1991}`, `first_name "Ivan"`, `last_name "P"`), `deathWithoutDateIsDead`, `unknownSexIsNull`, `familyFields` (`MARR` and `DIV` dates and places), `captureListsPartnersAndChildren` (a tree with one family: partners `[I1, I2]`, children `[I3]`, `geniId` from `_GENI_ID` or null).
- [x] **Step 2: Run** `$GRADLE :geni:testDebugUnitTest --tests '*FieldsTest*' --tests '*GeniParamsTest*' --tests '*LocalTreeTest*'` — FAIL (classes missing).
- [x] **Step 3: Implement** `parseDate`, `Fields.kt` and `LocalTree.kt`.
- [x] **Step 4: Run** the same command — PASS.
- [x] **Step 5: Commit** `Project people and families to the fields Geni has`.

### Task 3: GEDCOM → Geni parameters (`GeniParams`)

**Files:**
- Modify: `geni/src/main/java/app/familygem/geni/map/GeniParams.kt`
- Test: `geni/src/test/java/app/familygem/geni/map/GeniParamsTest.kt`

**Interfaces:**
- Consumes: `ProfileFields`, `UnionFields`, `EventFields` (Task 2).
- Produces: `GeniParams` gains `fun profile(old: ProfileFields, new: ProfileFields, isNew: Boolean = false): List<Pair<String, String>>; fun union(old: UnionFields, new: UnionFields): List<Pair<String, String>>`. An empty list means "nothing to upload".

Rules:
- Field groups, each sent only if it differs between `old` and `new` (or always when `isNew`):
  - names (`given`, `surname`, `maiden`, `suffix`, `nicknames`) → `first_name` (first word of `given`), `middle_name` (rest), `last_name`, `maiden_name`, `suffix`, `nicknames`; all of them, empty strings for nulls.
  - `sex` → `gender` = `male`/`female`; not sent when `new.sex` is null.
  - each of `birth`, `baptism`, `burial` → `<event>[date][day|month|year|circa]` (all four, empty strings for missing parts, `circa` = `true` or empty) and `<event>[location][place_name]` = place, plus `<event>[location][city|county|state|country]` = empty. If the new date is not parseable, the date parameters are left out and the date counts as unchanged (only a place change is sent; with no place change, nothing).
  - `dead`, `death`, `causeOfDeath` → `is_alive` = `true`/`false`; when dead also the `death[...]` parameters as above and `cause_of_death`.
  - `occupation` → `occupation`; `about` → `about_me`.
  - `isNew`: every non-empty group of `new`, plus `is_alive` always (Geni makes API-created profiles deceased otherwise).
- `union`: `marriage` and `divorce` groups as events above. A `DIV` without date and place (`DIV Y`) cannot be sent (it would need `status`): treat it as no divorce event. Never send `status`.

- [x] **Step 1: Write the failing tests** — `sendsOnlyChangedGroups` (only occupation changed → exactly `[occupation=Driver]`), `dateIsSentWhole` (birth year changed → `birth[date][day]`, `[month]`, `[year]`, `[circa]` all present), `placeClearsStructuredParts`, `deathSendsIsAliveFalse`, `aliveAgainSendsOnlyIsAlive`, `unparseableDateKeepsGeniDate` (new birth `"BEF 1900"`, same place → empty list; new place → only location params), `newProfileSendsIsAlive` (`isNew` with no `DEAT` → contains `is_alive=true` and `first_name`), `namesSplitGivenIntoFirstAndMiddle`, `unionNeverSendsStatus`.
- [x] **Step 2: Run** `$GRADLE :geni:testDebugUnitTest --tests '*GeniParamsTest*'` — FAIL.
- [x] **Step 3: Implement** `GeniParams`.
- [x] **Step 4: Run** the same command and the Task 2 tests — PASS.
- [x] **Step 5: Commit** `Map GEDCOM changes to Geni update parameters`.

### Task 4: Upload jobs in the sync store (Room v2)

**Files:**
- Create: `geni/src/main/java/app/familygem/geni/sync/UploadJob.kt`
- Modify: `geni/src/main/java/app/familygem/geni/sync/SyncStore.kt`, `RoomSyncStore.kt`, `FamilyMerger.kt` (carry `verifiedHash` over)
- Modify: `geni/src/test/java/app/familygem/geni/sync/InMemorySyncStore.kt`
- Test: `geni/src/androidTest/java/app/familygem/geni/sync/RoomSyncStoreTest.kt`, `geni/src/test/java/app/familygem/geni/sync/FamilyMergerTest.kt`

**Interfaces:**
- Produces:
  - `enum class JobKind { UPDATE_PROFILE, UPDATE_UNION, ADD_PARTNER, ADD_CHILD }`, `enum class JobState { PENDING, FAILED, DISMISSED }`
  - `data class UploadJob(val id: Long = 0, val treeId: Int, val key: String, val kind: JobKind, val geniId: String? = null, val personGedcomId: String? = null, val familyGedcomId: String? = null, val anchors: List<String> = emptyList(), val params: List<Pair<String, String>>, val label: String, val localHash: Int, val state: JobState = JobState.PENDING, val error: String? = null, val version: Int = 0)`. Keys: `profile:<geniId>`, `union:<geniId>`, `add:<personGedcomId>`.
  - `GeniLink` gains `val verifiedHash: Int? = null`: hash of the local projection last found equal to the snapshot.
  - `SyncStore` gains `suspend fun linkByGedcomId(treeId: Int, gedcomId: String): GeniLink?`, `suspend fun jobs(treeId: Int): List<UploadJob>` (by `id`), `suspend fun allJobs(): List<UploadJob>`, `suspend fun saveJob(job: UploadJob): Long` (insert when `id == 0`, else replace; returns the ID), `suspend fun deleteJob(id: Long)`. `deleteTree` also deletes the tree's jobs.
- Room: table `job` with a unique index on `(treeId, key)`; `anchors` and `params` stored as JSON (Gson) through a `TypeConverter`. Database version 2 with `Migration(1, 2)`: `ALTER TABLE link ADD COLUMN verifiedHash INTEGER` and `CREATE TABLE`/`CREATE UNIQUE INDEX` exactly as Room generates them for the entity (Room checks the schema on open). No destructive migration: existing links keep their snapshots.

- [x] **Step 1: Write the failing tests** — instrumented `migratesFromVersion1` (create a v1 database with the v1 `CREATE TABLE` statements through `SupportSQLiteOpenHelper`, insert a link, open with Room v2: the link is there with `verifiedHash == null`, a job can be saved and read back), `jobsRoundTrip` (params with brackets and empty values survive), `deleteTreeDeletesJobs`, `linkByGedcomId`; unit `mergeKeepsVerifiedHash` in `FamilyMergerTest`.
- [x] **Step 2: Run** `$GRADLE :geni:testDebugUnitTest --tests '*FamilyMergerTest*'` (FAIL) and, with the phone connected, `$GRADLE :geni:connectedDebugAndroidTest` (FAIL).
- [x] **Step 3: Implement** the entity, DAO queries, converter, migration and `InMemorySyncStore` methods.
- [x] **Step 4: Run** both commands — PASS.
- [x] **Step 5: Commit** `Store upload jobs and verified hashes in the sync database`.

### Task 5: `UploadPlanner`

**Files:**
- Create: `geni/src/main/java/app/familygem/geni/sync/UploadPlanner.kt`
- Test: `geni/src/test/java/app/familygem/geni/sync/UploadPlannerTest.kt`

**Interfaces:**
- Consumes: `LocalTree` (Task 2), `GeniParams` (Task 3), `GeniLink`, `UploadJob` (Task 4).
- Produces: `object UploadPlanner { fun plan(treeId: Int, local: LocalTree, links: List<GeniLink>, jobs: List<UploadJob>): Plan }` and `data class Plan(val upserts: List<UploadJob>, val deleteKeys: Set<String>, val verified: List<GeniLink>)`. `upserts` are in upload order and have `id = 0`; the caller merges them by key (Task 7).

Rules:
- A local person is linked when its `geniId` has a link whose `gedcomId` is the person's, or when it has no `geniId` but a link has its `gedcomId` (tag lost after an unsaved edit). A person with a `geniId` but no link is skipped (the next load relinks it).
- Linked person: if `link.verifiedHash == fields.hashCode()` skip it (keep its jobs). Else, with a snapshot: `params = GeniParams.profile(ProfileFields.ofSnapshot(snapshot), fields)`; empty → key `profile:<id>` in `deleteKeys` and the link with `verifiedHash = fields.hashCode()` in `verified`; else an `UPDATE_PROFILE` upsert with `localHash = fields.hashCode()`. Same for linked families with `UnionFields` and `UPDATE_UNION`.
- New people (not linked, no `geniId`): repeat until nothing changes — a new person in a family that has a linked member or a member already planned gets one job: `ADD_CHILD` if they are a child of that family, else `ADD_PARTNER`; `familyGedcomId` = that family; `anchors` = for `ADD_CHILD` the partners, for `ADD_PARTNER` the children then the other partners, as `"child:<gedcomId>"` / `"partner:<gedcomId>"` (the person excluded); `params = GeniParams.profile(ProfileFields.EMPTY, fields, isNew = true)`. Families are scanned in GEDCOM order, parent families before spouse families, so a chain is planned parent first.
- `deleteKeys` also gets: `add:` keys whose person is gone or now linked, `profile:`/`union:` keys whose record is gone (deleted locally: not uploaded in this plan).
- A family whose members are all linked but which has no Geni ID (a new link between existing profiles) gets no job.

- [x] **Step 1: Write the failing tests** — `unchangedPersonIsVerified`, `changedOccupationMakesUpdate` (params `[occupation=…]`, key `profile:profile-1`), `verifiedHashSkipsPerson` (keeps an existing job: not in `deleteKeys`), `revertedEditDeletesJob`, `newChildOfGeniUnionMakesAddChild`, `newParentOfGeniPersonMakesAddPartnerWithChildAnchor`, `chainOfNewPeopleIsOrdered` (new parent P and new child C of P's new family with a Geni partner: P's job before C's, C's anchors contain `partner:P`), `unconnectedNewPersonMakesNoJob`, `deletedPersonDropsItsJobs`, `linkBetweenExistingProfilesMakesNoJob`, `unparseableDateMakesNoJob`, `uploadedChangeIsNotPlannedAgain` (after the snapshot is replaced with the Geni version of the uploaded value, the plan is empty).
- [x] **Step 2: Run** `$GRADLE :geni:testDebugUnitTest --tests '*UploadPlannerTest*'` — FAIL.
- [x] **Step 3: Implement** `UploadPlanner`.
- [x] **Step 4: Run** the same command — PASS.
- [x] **Step 5: Commit** `Plan Geni uploads from the differences with the snapshots`.

### Task 6: `Uploader` (runs one job)

**Files:**
- Create: `geni/src/main/java/app/familygem/geni/sync/Uploader.kt`
- Test: `geni/src/test/java/app/familygem/geni/sync/UploaderTest.kt`

**Interfaces:**
- Consumes: `GeniClient.post`, `profiles`, `unions` (Task 1, plan 2), `SyncStore` (Task 4), `TreeHost.edit`, `GeniMapper.setGeniId`.
- Produces: `class Uploader(client: GeniClient, store: SyncStore, host: TreeHost, now: () -> Long = System::currentTimeMillis) { suspend fun run(job: UploadJob): Outcome }` and `sealed interface Outcome { data object Done; data class Retry(val reason: String); data class Failed(val message: String); data object NeedsLogin }`.

Rules:
- `UPDATE_PROFILE`: `post("<geniId>/update", params)`, then `profiles(listOf(geniId))` and save the link with the new `snapshot` and `geniUpdatedAt`, `verifiedHash = null`. No change to the GEDCOM. `UPDATE_UNION`: same with `union-U/update` and `unions(...)`.
- `ADD_CHILD`: the family's link (`linkByGedcomId(familyGedcomId)`) → `post("<union>/add-child")`; else the first anchor with a link → `post("<profile>/add-child")`. `ADD_PARTNER`: family link → `<union>/add-partner`; else the first linked `child:` anchor → `<profile>/add-parent`; else the first linked `partner:` anchor → `<profile>/add-partner`. No linked family or anchor → `Failed("depends on a change that was not uploaded")`.
- After an add: new profile ID = response `id`, union ID = `geniId(unions[0])`; fetch both with `profiles`/`unions`; in one `host.edit` set `_GENI_ID` on the person and, if not linked yet, on the family (skip records that no longer exist); save the person link (`fullyLoaded = false`, `loadedAtMillis = 0`) and the family link with their snapshots.
- Errors: `GeniAuthRequiredException` → `NeedsLogin`; `GeniApiException` with code 400–499 except 408 and 429 → `Failed(body message)`; other `GeniApiException`, `IOException` → `Retry`, except an `IOException` on the POST of an add job: the request may have reached Geni and created the profile, so a retry could create a second one → `Failed("connection lost while adding; check Geni, then retry")` (from the PR 1 review). A 400 may have applied part of the change: the snapshot is not touched; the next load brings Geni's state.

- [x] **Step 1: Write the failing tests** (MockWebServer + `FakeTreeHost` + `InMemorySyncStore`) — `updateProfilePostsParamsAndSavesSnapshot`, `addChildToLinkedUnion` (path `/api/union-10106/add-child`; person gets `_GENI_ID`, links saved), `addParentCreatesUnionLink` (family not linked, anchor child linked → `/api/profile-36430/add-parent`; family gets the union ID from `unions[0]`), `childOfNewPersonUsesTheNewUnion` (after the parent's add linked the family, the child's job posts to `union-…/add-child`), `missingAnchorFails`, `http403Fails`, `http503Retries`, `http401NeedsLogin`, `connectionLostOnAddFails`.
- [x] **Step 2: Run** `$GRADLE :geni:testDebugUnitTest --tests '*UploaderTest*'` — FAIL.
- [x] **Step 3: Implement** `Uploader`.
- [x] **Step 4: Run** the same command — PASS.
- [x] **Step 5: Commit** `Upload profile changes and new relatives to Geni`.

### Task 7: `SyncQueue` (reconcile and run)

**Files:**
- Create: `geni/src/main/java/app/familygem/geni/sync/SyncQueue.kt`
- Modify: `geni/src/main/java/app/familygem/geni/Geni.kt` (`fun queue(context): SyncQueue`, one instance)
- Test: `geni/src/test/java/app/familygem/geni/sync/SyncQueueTest.kt`

**Interfaces:**
- Consumes: `UploadPlanner`, `Uploader`, `SyncStore`.
- Produces: `class SyncQueue(store: SyncStore, uploader: Uploader) { suspend fun onSaved(treeId: Int, local: LocalTree); suspend fun runPending(treeId: Int): RunResult; suspend fun retryFailed(); suspend fun dismissFailed() }` and `enum class RunResult { EMPTY, RETRY_LATER, NEEDS_LOGIN }`.

Rules:
- One `Mutex` per tree guards reading and writing jobs; it is never held during network calls.
- `onSaved`: `plan(...)`, then for each upsert: no job with that key → insert; same `params` → keep the existing job as it is (also a `FAILED` or `DISMISSED` one); different `params` → update `params`, `anchors`, `label`, `localHash`, `state = PENDING`, `error = null`, `version + 1`, keeping its `id` (and so its place in the order). Delete jobs in `deleteKeys`; save `verified` links.
- `runPending`: take the first `PENDING` job, run it without the lock; then under the lock re-read it: `Done` → `deleteJobIfVersion(id, version)` (a newer version planned meanwhile stays pending: the user edited during the upload); `Failed` → `state = FAILED`, `error`; `Retry` → return `RETRY_LATER`; `NeedsLogin` → return `NEEDS_LOGIN`. Loop until no pending job → `EMPTY`.
- `retryFailed`: all `FAILED` → `PENDING`. `dismissFailed`: all `FAILED` → `DISMISSED` (kept so the same change is not planned again; a new edit of the record changes `params` and revives it).

- [x] **Step 1: Write the failing tests** (fake `Uploader` behaviour through MockWebServer or an `Uploader` subclass seam — make `Uploader.run` `open` for tests) — `samePlanTwiceKeepsOneJob`, `changedParamsResetFailedJob`, `dismissedJobStaysDismissedForSameParams`, `runsJobsInOrderAndDeletesDone`, `editDuringUploadKeepsNewerVersion` (`onSaved` with new params while `run` is suspended → after `Done` the job is still pending with the new params), `transientErrorKeepsJobPending`, `authErrorStopsTheQueue`, `failedJobIsKeptAndSkipped`.
- [x] **Step 2: Run** `$GRADLE :geni:testDebugUnitTest --tests '*SyncQueueTest*'` — FAIL.
- [x] **Step 3: Implement** `SyncQueue` and `Geni.queue`.
- [x] **Step 4: Run** `$GRADLE :geni:testDebugUnitTest` — all PASS.
- [x] **Step 5: Commit** `Queue Geni uploads with ordering, retries and failures`.

### Task 8: Hook on save and background runner in the app

**Files:**
- Modify: `app/src/main/java/app/familygem/util/TreeUtil.kt` (one line, last line of `saveJson`)
- Create: `app/src/geni/java/app/familygem/geni/GeniSync.kt`
- Modify: `app/src/geni/java/app/familygem/geni/GeniGlue.kt` (`suspend fun onTreeSaved(gedcom: Gedcom, treeId: Int)`; `install()` also calls `GeniSync.resume(context)`)
- Modify: `geni/src/main/java/app/familygem/geni/ui/GeniLoginActivity.kt` (after a successful login call a `Geni.onLoggedIn` callback that the glue sets to `GeniSync.resume`)
- Modify: `geni/UPSTREAM-HOOKS.md`

**Interfaces:**
- Consumes: `SyncQueue` (Task 7), `LocalTree.capture` (Task 2).
- Produces: hook line `app.familygem.geni.GeniGlue.onTreeSaved(gedcom, treeId) // GENI-HOOK`; `object GeniSync { fun onSaved(context, treeId, local: LocalTree); fun kick(context, treeId); fun resume(context) }`; `Geni.resumeUploads: (() -> Unit)?` (called after login and "Retry all").

Rules:
- `onTreeSaved` returns at once for a tree with no `store.tree(treeId)`; for a Geni tree it captures `LocalTree` on the calling thread (like upstream's JSON write, which reads the same tree there) and hands it to `GeniSync.onSaved`, which runs `queue.onSaved` then `kick` in its scope (`SupervisorJob + Dispatchers.IO`).
- `kick` runs `runPending` once per tree at a time (a second kick while running marks "run again"). `RETRY_LATER` → wait 30 s, doubling up to 30 min, then kick again; the delay resets after an `EMPTY`. `NEEDS_LOGIN` → stop until `resume`.
- `resume`: kick every tree with pending jobs (`store.allJobs()`); called from `install()` and after login.
- `runPending` does not throw for upload errors, but the runner still catches any exception (log, retry later with back-off) so the scope never dies. (PR 2 review)
- Log with tag `GeniSync`: job kind, key and outcome; never params or tokens.

- [x] **Step 1: Add the hook and glue**; add the hook row to `geni/UPSTREAM-HOOKS.md`.
- [x] **Step 2: Build** `$GRADLE :app:assembleDebug :geni:testDebugUnitTest` — BUILD SUCCESSFUL, all tests PASS.
- [x] **Step 3: Commit** `Plan and run Geni uploads when a Geni tree is saved`.

### Task 9: Pending and failed changes on the account screen

**Files:**
- Modify: `geni/src/main/java/app/familygem/geni/ui/GeniLoginActivity.kt`, `geni/src/main/res/layout/geni_activity_login.xml`
- Modify: `geni/src/main/res/values/geni_strings.xml`, `geni/src/main/res/values-ru/geni_strings.xml`

**Interfaces:**
- Consumes: `SyncStore.allJobs`, `SyncQueue.retryFailed`, `dismissFailed`, `Geni.onLoggedIn`.

Rules:
- Under the status line: "Changes waiting for upload: N" (pending jobs, hidden when 0) and a button "Failed changes (M)" (hidden when 0).
- Failed adds carry an error that says to check Geni first (the profile may exist): the dialog shows that text, and "Retry all" warns before retrying adds. (PR 2 review)
- The button opens an `AlertDialog` listing each failed job as `label` and `error`, with buttons "Retry all" (`retryFailed`, then `resume`), "Dismiss all" (`dismissFailed`) and "Close". Counts refresh after each action and in `onResume`.
- Strings (en / ru): `geni_pending_changes` "Changes waiting for upload: %1$d" / "Изменений ждут отправки: %1$d"; `geni_failed_changes` "Failed changes (%1$d)" / "Не отправлено (%1$d)"; `geni_retry_all` "Retry all" / "Повторить все"; `geni_dismiss_all` "Dismiss all" / "Скрыть все"; `geni_close` "Close" / "Закрыть".

- [x] **Step 1: Implement** the layout, strings and dialog.
- [x] **Step 2: Build** `$GRADLE :app:assembleDebug` — BUILD SUCCESSFUL.
- [x] **Step 3: Commit** `Show pending and failed Geni changes on the account screen`.

### Task 10: Check on the sandbox and on the phone

**Files:**
- Modify: `docs/superpowers/specs/2026-11-16-geni-sync-design.md` (findings), `docs/superpowers/plans/README.md` (ticks)

- [x] **Step 1: Install** the debug build on the phone (`adb install --user 0 -r app/build/outputs/apk/debug/app-debug.apk`), log in, create the Geni tree.
- [x] **Step 2: Edit and check on sandbox.geni.com** (the user looks at the website): change a birth date and place of an existing person; change occupation; add a child to the user and his wife; add a parent to a person without parents; add a spouse, then a child of that new couple, while in airplane mode, then turn the network on. `adb logcat -s GeniSync` shows each job `Done`; the website shows the changes; a second save makes no new job.
- [x] **Step 3: Check failures** (skipped on the phone: no unmanaged profile on the sandbox; failed adds from airplane mode were retried from the account screen instead): edit a profile the user does not manage (if the sandbox has one) or force a 4xx; the account screen shows it under "Failed changes"; "Dismiss all" hides it.
- [x] **Step 4: Write findings** to the spec ("To verify on the sandbox"); tick the stage 3 items in `docs/superpowers/plans/README.md`.
- [x] **Step 5: Commit** `Record write path findings`, then (after the user agrees) push `geni-write-3` and open a PR into `geni`.
