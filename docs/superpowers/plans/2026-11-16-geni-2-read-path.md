# Geni Sync, Plan 2: Read Path — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a Geni tree from the logged-in user's profile, fill it lazily from Geni (person opened, background prefetch to N steps, "Keep offline"), and show it with FamilyGem's normal screens.

**Architecture:** Geni JSON is parsed into small Kotlin models, mapped into the GEDCOM model (`org.folg.gedcom`) by a pure mapper that updates existing records in place, and linked to Geni IDs through a `SyncStore` (Room). `:geni` cannot see upstream classes (`:app` depends on `:geni`), so it talks to the app through a `TreeHost` interface; its implementation (`AppTreeHost`) lives in Geni-owned files inside the app module (`app/src/geni/java`, added by `app/geni.gradle`). Each load uses 3 requests: `immediate-family`, then batch `profile?ids=` and `union?ids=`.

**Tech Stack:** Kotlin, gedcom5-java 1.15.0, Room 2.8.5 with KSP 2.3.13, WorkManager 2.11.2, Gson, OkHttp, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-16-geni-sync-design.md` (sections "Geni tree", "Mapping GEDCOM to Geni", "Loading (lazy cache)").

## Global Constraints

- Upstream files are touched only by one-line `// GENI-HOOK` lines listed in `geni/UPSTREAM-HOOKS.md`.
- Geni is the source of truth; this plan only reads from Geni. No uploads.
- Geni metadata lives in the `:geni` database keyed by tree ID, not in `Settings.Tree`.
- Each mapped `INDI`/`FAM` carries its Geni ID as custom tag `_GENI_ID` (a `GedcomTag` in the `ModelParser.MORE_TAGS_EXTENSION_KEY` extension).
- The mapper updates existing `Person`/`Family` objects in place (open screens hold references to them) and never touches data Geni does not have (sources, notes, media, other events).
- Rate limit: the shared `RateLimiter` from plan 1; the sandbox limit is 3 requests per 1 s (from headers).
- Prefetch radius N defaults to 3 relationship steps from the root.
- Build and test: `JAVA_HOME=/home/vp/jdk $GRADLE ...` with `$GRADLE = ~/.gradle/wrapper/dists/gradle-9.9.0-bin/*/gradle-9.9.0/bin/gradle`. If the IDE's Gradle daemons corrupt `app/build`, run the check in an rsync copy of the repo in the scratchpad.

Geni facts this plan relies on (sandbox, 2026-11-16; fixtures in `geni/src/test/resources/geni/`):
- `GET profile-X/immediate-family` → `{focus, nodes}`. Node keys are `profile-N` or `union-N`. Profile nodes have only `id, first_name, last_name, gender, is_alive, edges`; union nodes only `url, status, edges`. `edges` maps the other node ID to `{"rel": "partner" | "child"}`.
- `GET profile?ids=a,b&fields=...` and `GET union?ids=a,b` → `{"results": [...]}` with full objects.
- Union: `partners`, `children` (lists of API URLs ending in the ID), `status` (`spouse`/`ex_spouse`), `marriage`, `divorce` events.
- Event: `{name, date: {year, month, day, circa, formatted_date}, location: {place_name, city, county, state, country, formatted_location}}`, all parts optional.
- `nicknames` is a list of strings; `is_alive` is boolean; `updated_at` is a Unix time string; `mugshot_urls` maps size to URL.

## Review Focus

- A person present in two loads (opened from the father, then from the root): one `INDI`, not two; links merged. Test in Task 2 (`loadingTwiceDoesNotDuplicate`).
- A profile with no date parts, only a year, or `circa`: GEDCOM date is empty, `1960`, or `ABT 1960`; never "null". Test in Task 1.
- A partner with unknown gender: still placed in the family (husband slot if free, else wife). Test in Task 1.
- Opening a person with no network: the screen shows cached data, no error dialog, no crash. Test in Task 3 (`networkErrorLeavesTreeUnchanged`).
- Loading while the user edits the same open tree: the mapper runs on the main thread against `Global.gc` and saves once; user edits are not lost. Manual check in Task 5.

---

### Task 1: Geni models and GEDCOM mapper

**Files:**
- Modify: `geni/build.gradle` (add `implementation 'org.familysearch.gedcom:gedcom:1.15.0'`)
- Create: `geni/src/main/java/app/familygem/geni/api/GeniModels.kt` (replaces `GeniProfile.kt`)
- Create: `geni/src/main/java/app/familygem/geni/map/GeniMapper.kt`
- Test: `geni/src/test/java/app/familygem/geni/api/GeniModelsTest.kt`, `geni/src/test/java/app/familygem/geni/map/GeniMapperTest.kt`

**Interfaces:**
- Produces:
  - `data class GeniDate(year: Int?, month: Int?, day: Int?, circa: Boolean?)`, `data class GeniLocation(placeName, city, county, state, country: String?)` (Gson names `place_name` etc.), `data class GeniEvent(date: GeniDate?, location: GeniLocation?)`.
  - `GeniProfile` gains: `middleName, maidenName, suffix, gender, isAlive: Boolean?, occupation, aboutMe, causeOfDeath, nicknames: List<String>?, birth, death, baptism, burial: GeniEvent?, deleted: Boolean?, mergedInto: String?, mugshotUrls: Map<String, String>?`.
  - `data class GeniUnion(id: String, status: String?, partners: List<String>?, children: List<String>?, marriage: GeniEvent?, divorce: GeniEvent?)`.
  - `data class ImmediateFamily(focusId: String, profileIds: List<String>, unionIds: List<String>)` with `companion fun parse(json: String): ImmediateFamily`.
  - `fun geniId(urlOrId: String): String` (last path segment).
  - `object GeniMapper { fun gedcomDate(date: GeniDate?): String?; fun place(location: GeniLocation?): String?; fun applyProfile(gedcom: Gedcom, person: Person, profile: GeniProfile); fun applyUnion(gedcom: Gedcom, family: Family, union: GeniUnion, personFor: (String) -> Person?) }`.
  - `const val GENI_ID_TAG = "_GENI_ID"`; `fun getGeniId(container: ExtensionContainer): String?`; `fun setGeniId(container: ExtensionContainer, id: String)`.

Mapping rules (the spec left the field list to sandbox findings):
- Name: one `Name` "given /surname/" from `first_name` + `middle_name` and `last_name`, with `NSFX` from `suffix`, `NICK` from `nicknames` joined by ", ". If `maiden_name` differs from `last_name`, a second `Name` "given /maiden/" with `TYPE birth`.
- `gender` male/female → `SEX M`/`F`, otherwise `U`.
- `birth`/`baptism`/`death`/`burial` → `BIRT`/`BAPM`/`DEAT`/`BURI` with `DATE` and `PLAC`; `cause_of_death` → `CAUS` of `DEAT`. `is_alive == false` with no death event → `DEAT Y`.
- `occupation` → `OCCU`. `about_me` → custom tag `_GENI_ABOUT`.
- Photos (`mugshot_urls`) are parsed but not mapped in this plan; showing Geni photos is a later task in the roadmap.
- Date: `day MON year` with English three-letter month, upper case (`12 MAR 1960`, `JUN 2012`, `2014`); `circa` → `ABT ` prefix; no year → null.
- Place: non-blank `place_name, city, county, state, country` joined by ", ".
- Union: partners → `HUSB` (male) / `WIFE` (female), unknown gender takes the free slot (husband first); `children` → `CHIL`; `marriage` → `MARR`; `divorce` or `status == ex_spouse` → `DIV` (`DIV Y` without data). Person side gets matching `FAMS`/`FAMC` refs, never duplicated.
- In-place update: `applyProfile` replaces only the names, `SEX`, and the facts with tags `BIRT, BAPM, DEAT, BURI, OCCU` that it manages, plus `_GENI_ABOUT`; any other fact, note, source or media stays.

- [x] **Step 1: Write failing tests**
  - `GeniModelsTest`: `parsesImmediateFamilyNodes` (root fixture: `focusId == "profile-36430"`, 8 profile IDs, unions `union-10105`, `union-10106`); `parsesProfilesBatch` (8 results; `profile-36431` has `death.date.day == 15`, `burial.location.placeName == "Vvedenskoye Cemetery"`; `profile-36432` `nicknames == listOf("Masha")`); `parsesUnionsBatch` (`union-10106`: 2 partners, 3 children, `marriage.date.month == 6`); `geniIdFromUrl` (`"https://api.sandbox.geni.com/profile-36431"` → `"profile-36431"`).
  - `GeniMapperTest`: `fullDate` (`12 MAR 1960`), `monthYear` (`JUN 2012`), `yearOnly` (`2014`), `circa` (`ABT 1960`), `noYearIsNull`; `placeJoinsParts` (`"Vvedenskoye Cemetery, Moscow"`); `profileMapsNamesSexEvents` (father: `Ivan /P/`, `SEX M`, `BIRT` `12 MAR 1960` `Moscow, Russia`, `DEAT` `15 JAN 2020`, `BURI` place, `OCCU Teacher`, `_GENI_ABOUT`); `maidenNameAddsBirthName` (mother: second name `Maria /Smirnova/` type `birth`, `NICK Masha`); `deadWithoutDeathEventGetsDeatY`; `applyProfileKeepsOtherData` (a person with a `NOTE` and a `RESI` fact keeps both after `applyProfile`); `applyProfileTwiceDoesNotDuplicateFacts`; `unionLinksPartnersAndChildren` (union-10106: `HUSB` = root person, `WIFE` = Anna, 3 `CHIL`; each child has one `FAMC` to it); `unknownGenderPartnerTakesFreeSlot`; `geniIdTagRoundTrip`.
- [x] **Step 2: Run, expect compile failure** — `$GRADLE :geni:testDebugUnitTest`
- [x] **Step 3: Implement models and `GeniMapper`; make `GeniClient.currentProfile()` use the new `GeniProfile`; delete `GeniProfile.kt`**
- [x] **Step 4: Run, expect pass** — `$GRADLE :geni:testDebugUnitTest`
- [x] **Step 5: Commit** — `git commit -m "Add Geni models and GEDCOM mapper"`

### Task 2: Sync store (Room) and family merge

**Files:**
- Modify: root `build.gradle` (one line in `buildscript.dependencies`: `classpath 'com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:2.3.13' // GENI-HOOK`), `geni/UPSTREAM-HOOKS.md`
- Modify: `geni/build.gradle` (plugin `com.google.devtools.ksp`; `androidx.room:room-runtime:2.8.5`, `room-ktx:2.8.5`, `ksp 'androidx.room:room-compiler:2.8.5'`; `androidTestImplementation` `androidx.test.ext:junit-ktx:1.3.0`, `androidx.test:runner:1.7.0`)
- Create: `geni/src/main/java/app/familygem/geni/sync/SyncStore.kt`, `geni/src/main/java/app/familygem/geni/sync/RoomSyncStore.kt`
- Create: `geni/src/main/java/app/familygem/geni/sync/FamilyMerger.kt`
- Test: `geni/src/test/java/app/familygem/geni/sync/FamilyMergerTest.kt`, `geni/src/test/java/app/familygem/geni/sync/InMemorySyncStore.kt`, `geni/src/androidTest/java/app/familygem/geni/sync/RoomSyncStoreTest.kt`

**Interfaces:**
- Consumes: Task 1 models and `GeniMapper`.
- Produces:
  - `data class GeniTreeInfo(treeId: Int, rootGeniId: String, prefetchDepth: Int = 3)`.
  - `data class GeniLink(treeId: Int, geniId: String, gedcomId: String, fullyLoaded: Boolean, geniUpdatedAt: String?, snapshot: String?, loadedAtMillis: Long)` (`snapshot` = raw Geni JSON of the object, for plan 3).
  - `interface SyncStore { suspend fun tree(treeId: Int): GeniTreeInfo?; suspend fun saveTree(info: GeniTreeInfo); suspend fun link(treeId: Int, geniId: String): GeniLink?; suspend fun links(treeId: Int): List<GeniLink>; suspend fun saveLinks(links: List<GeniLink>); suspend fun deleteTree(treeId: Int) }`.
  - `class RoomSyncStore(context: Context) : SyncStore` (database `geni_sync.db`).
  - `object FamilyMerger { fun merge(gedcom: Gedcom, treeId: Int, family: ImmediateFamily, profiles: List<GeniProfile>, unions: List<GeniUnion>, rawProfiles: Map<String, String>, rawUnions: Map<String, String>, existing: Map<String, GeniLink>, nowMillis: Long): List<GeniLink> }` — finds or creates each `Person`/`Family` by link (fallback: `_GENI_ID` tag scan), applies the mapper, returns the links to save. New GEDCOM IDs come from `gedcom` (`I1`, `F1`… next free number, like upstream). The focus profile and every profile fetched with full fields get `fullyLoaded = true`; nothing else is created.

- [ ] **Step 1: Write failing tests** (`FamilyMergerTest`, JVM, fixtures): `mergeCreatesPeopleAndFamilies` (root fixtures → 8 `INDI`, 2 `FAM`, 10 links); `loadingTwiceDoesNotDuplicate` (merge root, then father fixtures with the returned links → no duplicate `INDI`; father's own parents added if present); `mergeUpdatesInPlace` (same `Person` instance before and after a second merge); `tagFallbackWhenLinkMissing` (empty `existing`, people already tagged → reused).
- [ ] **Step 2: Run, expect failure** — `$GRADLE :geni:testDebugUnitTest`
- [ ] **Step 3: Implement `SyncStore`, `InMemorySyncStore` (test), `FamilyMerger`, then `RoomSyncStore` (entities `GeniTreeEntity`, `GeniLinkEntity` with primary key `(treeId, geniId)`, one DAO)**
- [ ] **Step 4: Run JVM tests, expect pass** — `$GRADLE :geni:testDebugUnitTest`
- [ ] **Step 5: Instrumented test on the connected phone** — `RoomSyncStoreTest`: `savesAndReadsTree`, `upsertsLinks` (same key twice → one row, latest values), `deleteTreeRemovesLinks`. Run: `$GRADLE :geni:connectedDebugAndroidTest` → PASS (needs the phone over adb).
- [ ] **Step 6: Build the app** — `$GRADLE :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 7: Commit** — `git commit -m "Add Geni sync store and family merge"`

### Task 3: Loader and tree host

**Files:**
- Create: `geni/src/main/java/app/familygem/geni/TreeHost.kt`, `geni/src/main/java/app/familygem/geni/sync/GeniLoader.kt`
- Modify: `geni/src/main/java/app/familygem/geni/api/GeniClient.kt` (typed batch calls)
- Test: `geni/src/test/java/app/familygem/geni/sync/GeniLoaderTest.kt`, `geni/src/test/java/app/familygem/geni/FakeTreeHost.kt`

**Interfaces:**
- Consumes: `GeniClient.getJson`, `FamilyMerger`, `SyncStore`.
- Produces:
  - `interface TreeHost { suspend fun createTree(title: String, gedcom: Gedcom): Int; suspend fun <T> edit(treeId: Int, block: (Gedcom) -> T): T; fun refreshScreens() }` — `edit` runs `block` on the open `Global.gc` (main thread) when `treeId` is the open tree, otherwise on a temporarily read tree, then saves the JSON once; one call at a time (mutex).
  - `GeniClient`: `suspend fun immediateFamily(profileId: String): Pair<ImmediateFamily, String>`; `suspend fun profiles(ids: List<String>): Map<String, Pair<GeniProfile, String>>`; `suspend fun unions(ids: List<String>): Map<String, Pair<GeniUnion, String>>` (object plus its raw JSON; `fields` = the profile field list of this plan).
  - `class GeniLoader(client: GeniClient, store: SyncStore, host: TreeHost, now: () -> Long) { suspend fun load(treeId: Int, geniProfileId: String): LoadResult }`, `sealed interface LoadResult { data class Loaded(newPeople: Int) ; data object NotAGeniTree; data object NeedsLogin; data class Failed(message: String) }`.
  - `suspend fun GeniLoader.createTree(title: String): Int` — current profile → empty Gedcom with header from the host → `load` of the root → `GeniTreeInfo(treeId, rootId)`.

- [ ] **Step 1: Write failing tests** (MockWebServer serving fixtures, `FakeTreeHost` with an in-memory Gedcom, `InMemorySyncStore`): `loadFetchesThreeRequestsAndMerges` (3 requests: `immediate-family`, `profile?ids=`, `union?ids=`; tree has 8 people); `networkErrorLeavesTreeUnchanged` (500 on the batch call → `Failed`, tree and store untouched); `authErrorReturnsNeedsLogin`; `notAGeniTree` (no `GeniTreeInfo` → `NotAGeniTree`, no request); `createTreeStoresInfo`.
- [ ] **Step 2: Run, expect failure**
- [ ] **Step 3: Implement** — fetch everything first, then one `host.edit` with `FamilyMerger.merge`, then `store.saveLinks`, then `host.refreshScreens()`.
- [ ] **Step 4: Run, expect pass** — `$GRADLE :geni:testDebugUnitTest`
- [ ] **Step 5: Commit** — `git commit -m "Add Geni loader"`

### Task 4: App glue and "Create Geni tree"

**Files:**
- Modify: `app/geni.gradle` (`android.sourceSets.main.java.srcDirs += 'src/geni/java'`)
- Create: `app/src/geni/java/app/familygem/geni/AppTreeHost.kt`, `app/src/geni/java/app/familygem/geni/GeniGlue.kt`
- Modify: `app/src/main/java/app/familygem/TreesActivity.kt` (the existing hook line calls `app.familygem.geni.GeniGlue.addTreesMenu(this, bar.menu)` instead), `geni/UPSTREAM-HOOKS.md`
- Modify: `geni/src/main/java/app/familygem/geni/Geni.kt` (`var host: TreeHost?`, `fun loader(context): GeniLoader`, `fun store(context): SyncStore`), `GeniLoginActivity` + strings

**Interfaces:**
- Consumes: `TreeHost`, `GeniLoader.createTree`.
- Produces: `object GeniGlue { fun install(context: Context); fun addTreesMenu(activity: Activity, menu: Menu); fun onPersonOpened(activity: Activity, person: Person?, refresh: () -> Unit); fun addProfileMenu(activity: Activity, menu: Menu, person: Person?) }` — `install` sets `Geni.host = AppTreeHost` once; every glue function calls it first.
- `AppTreeHost` uses upstream code: `TreeUtil.createHeader`, `TreeUtil.saveJson`, `TreeUtil.openGedcomTemporarily`, `Settings.addTree`/`max`, `Global.gc`, `Global.settings.openTree`, `Global.edited = true` for `refreshScreens`.

Login screen: when logged in, button `geni_create_tree` ("Create Geni tree" / "Создать дерево Geni"), hidden if a Geni tree already exists for this account. Tree title: display name of the root profile. On success: toast `geni_tree_created`, finish.

- [ ] **Step 1: Implement glue, host, button, strings; update the hook line**
- [ ] **Step 2: Build and run all unit tests** — `$GRADLE :geni:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug` → PASS.
- [ ] **Step 3: Manual on the phone** — log in, "Create Geni tree": a new tree in the list with 8 people; the diagram shows parents, wife, 3 children, brother; father's card shows death, burial, occupation, `_GENI_ABOUT`.
- [ ] **Step 4: Commit** — `git commit -m "Create a Geni tree from the logged-in profile"`

### Task 5: Lazy load on person open

**Files:**
- Modify: `app/src/main/java/app/familygem/profile/ProfileActivity.kt` (one line after `Global.indi = person?.id` in `onCreate`: `app.familygem.geni.GeniGlue.onPersonOpened(this, person) { refresh() } // GENI-HOOK`), `geni/UPSTREAM-HOOKS.md`
- Modify: `app/src/geni/java/app/familygem/geni/GeniGlue.kt`; Create: `geni/src/main/java/app/familygem/geni/sync/LoadPolicy.kt`
- Test: `geni/src/test/java/app/familygem/geni/sync/LoadPolicyTest.kt`

**Interfaces:**
- Produces: `object LoadPolicy { fun shouldLoad(link: GeniLink?, nowMillis: Long, refreshAfterMillis: Long = 24 * 3600 * 1000): Boolean }` — true when the person has a link and it is not fully loaded or older than `refreshAfterMillis`.
- `onPersonOpened`: only for Geni trees and people with `_GENI_ID`; runs `loader.load` in the activity's `lifecycleScope`; on `Loaded` calls `refresh`; `NeedsLogin` shows a toast once; `Failed` is silent (cached data stays).

- [ ] **Step 1: Failing tests** — `notLoadedPersonLoads`, `freshPersonSkips`, `stalePersonLoads`, `personWithoutLinkSkips`.
- [ ] **Step 2: Run, expect failure**; **Step 3: implement**; **Step 4: run, expect pass** — `$GRADLE :geni:testDebugUnitTest`
- [ ] **Step 5: Manual on the phone** — open Sasha (not fully loaded): card refreshes with full birth date and place; airplane mode: opening a person shows cached data, no error; edit a name while a load runs: the edit is kept.
- [ ] **Step 6: Commit** — `git commit -m "Load Geni relatives when a person is opened"`

### Task 6: Background prefetch and "Keep offline"

**Files:**
- Create: `geni/src/main/java/app/familygem/geni/sync/Prefetcher.kt`, `geni/src/main/java/app/familygem/geni/sync/PrefetchWorker.kt`
- Modify: `geni/build.gradle` (`androidx.work:work-runtime:2.11.2`), `app/src/geni/java/app/familygem/geni/GeniGlue.kt`, `app/src/main/java/app/familygem/profile/ProfileActivity.kt` (one line before `return true` in `onCreateOptionsMenu`: `app.familygem.geni.GeniGlue.addProfileMenu(this, menu, person) // GENI-HOOK`), `geni/UPSTREAM-HOOKS.md`, strings
- Test: `geni/src/test/java/app/familygem/geni/sync/PrefetcherTest.kt`

**Interfaces:**
- Produces:
  - `class Prefetcher(loader: suspend (String) -> LoadResult, relatives: suspend (String) -> List<String>, isFresh: suspend (String) -> Boolean) { suspend fun run(startGeniId: String, depth: Int): Int }` — breadth-first over relationship steps (parents, partners, children, siblings count as one step), loads each person at most once, skips fresh ones, stops on `NeedsLogin`, returns the number of loads.
  - `PrefetchWorker` (CoroutineWorker, unique work `geni-prefetch-<treeId>`, network constraint): input `treeId`, `startGeniId`, `depth`. Enqueued after tree creation (root, `prefetchDepth`) and by "Keep offline".
  - Profile menu item `geni_keep_offline` ("Keep offline" / "Хранить офлайн") for Geni trees: a dialog with depth 1–5 (default 3), then the worker from that person.

- [ ] **Step 1: Failing tests** (fake graph): `visitsWithinDepthOnly` (depth 1 from root → root + direct relatives), `eachPersonOnce` (cycle through spouse/children), `skipsFreshPeople`, `stopsOnNeedsLogin`.
- [ ] **Step 2: Run, expect failure**; **Step 3: implement**; **Step 4: run, expect pass** — `$GRADLE :geni:testDebugUnitTest`
- [ ] **Step 5: Manual on the phone** — after creating the tree, the worker loads radius 3 (grand-parents' level, if any); "Keep offline" on the father with depth 2 runs and finishes.
- [ ] **Step 6: Commit** — `git commit -m "Prefetch Geni relatives in background"`

### Task 7: Measure

- [ ] **Step 1:** On the sandbox tree, time a full radius-3 prefetch (log start/end in the worker at debug level) and the size of `<treeId>.json`; time `TreeUtil.saveJson` for the tree.
- [ ] **Step 2:** Record numbers in the spec section "To verify on the sandbox", with the sandbox rate limit (3 per 1 s) noted; say whether approach A still looks fine.
- [ ] **Step 3: Commit** — `git commit -m "Record Geni prefetch measurements"`
