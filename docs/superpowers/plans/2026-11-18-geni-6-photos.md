# Geni photos Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show Geni photos (main photo and album) in a Geni tree, and send added, main and deleted photos to Geni.

**Architecture:** A Geni photo becomes an ordinary FamilyGem media file linked to its Geni photo ID by a Room row (`photo_links`). Reads extend the loader; writes add upload job types to the existing queue. Photo hosts get no token. Five PRs, each into `geni`.

**Tech Stack:** Kotlin, OkHttp, Gson, Room, coroutines; JUnit 4 and MockWebServer for tests.

**Spec:** [2026-11-18-geni-photos-design.md](../specs/2026-11-18-geni-photos-design.md)

## Global Constraints

- Photo code in `:geni`, Kotlin; upstream touched only through small hooks.
- `Authorization` is sent only to the Geni API host, never to a photo host.
- Photo downloads and API calls go through the rate limiter where the host is the API.
- Only media with a `photo_links` row, or chosen by the user, is uploaded; nothing is deleted on Geni without confirmation.
- Never commit tokens or `geni.properties`; ask before every commit and every GitHub comment (project memory: stage mode may be autonomous only if the user says so).
- Task 1 is done (2026-11-18, sandbox): findings in the "Task 1 findings" section below override anything else in this plan.

## Review Focus

- Photo URL that expires or needs a token: download fails with a clear error and the main photo keeps its old local file.
- A downloaded file that is not an image (HTML error page): rejected, not saved as media.
- Very large photo (tens of MB): download stops at the size cap; upload is scaled down first.
- Profile with no photo (`mugshot_urls` null or empty): no media is created and an old Geni-linked photo is removed only when Geni says it is gone.
- Same photo uploaded twice after a failed response: not duplicated (job state kept as for profile adds).

## Task 1 findings (sandbox, 2026-11-18)

- `GET profile-X/photos` → `{"results":[photo,...],"total_count":N}`; an empty album is `{"results":[]}`. Each photo: `id` ("photo-2175"), `guid`, `created_at`, `updated_at` (Unix seconds as strings), `content_type`, `url` (API URL), `title`, `album_id`, `sizes` (map: `large, medium, small, thumb, thumb2, print, original, url`), `tags` (list of profile API URLs). `GET photo-X` returns the same object.
- `profile?fields=mugshot_urls` → `{"id":..,"mugshot_urls":{large,medium,small,thumb,print,thumb2,original,url}}` where `url` is the photo's API URL (`.../photo-2175`): the photo ID of the main photo. No photo → `mugshot_urls` absent.
- Photo file URLs are on `sandbox-photos.geni.com` (https), signed (`?hash=<hex>.<expiry>`, expiry about 1 day ahead), and download with no token (HTTP 200, `image/jpeg`).
- Upload: `POST profile-X/add-photo` with form `file` (base64), `file_name`, `title` returns the photo object (new `id`); content type becomes `image/jpeg`. The first photo of a profile becomes its main photo; later uploads do not.
- Delete: `POST photo-X/delete` → `{"result":"Deleted"}`; deleting the main photo leaves the profile with none. `POST photo-X/update` with `title` works.
- Setting an existing photo as main: no call found (`mugshot_id` on `profile-X/update`, `photo-X/set-mugshot`, `profile-X/add-mugshot`, `photo-X/make-main`, `photo-X/set-main` all do nothing). So `SET_MAIN_PHOTO` is dropped from the plan; `addPhoto` has no `makeMain` flag.

Fixtures: `geni/src/test/resources/geni/photo-list.json`, `photo-single.json`, `profile-mugshot.json` (real responses, URL signatures replaced) and `photo-upload-response.json` (an edited copy of `photo-single.json` with the id and title changed: the real upload response has the same shape).

## PR 1: API client (Tasks 1–4)

### Task 1: Sandbox check of the photo endpoints (DONE, see findings)

**Files:**
- Modify: `app/src/androidTest/java/app/familygem/geni/GeniSandboxTool.kt` (new commands `photos`, `photo`)
- Create: `geni/src/test/resources/geni/photo-list.json`, `photo-single.json`, `photo-upload-response.json` (real responses with names and IDs shortened)
- Modify: memory `geni-api.md`

**Interfaces:**
- Produces: the settled endpoint names, field names, photo sizes, upload size limit, whether URLs need a token or expire, and the delete and set-main calls. Later tasks use exactly these.

- [x] **Step 1: Using the existing dev tool on the phone (user logged in on the sandbox), request `profile-X/photos`, `photo-Y`, and `profile-X?fields=mugshot_urls`; save the raw JSON.**
- [x] **Step 2: Try the calls for: upload to album without setting main (`add-photo` flag or other call), set main, delete (`photo-Y/delete`) on a throwaway profile; record each result and HTTP code.**
- [x] **Step 3: Fetch a photo URL once without and once with the token header; note host, expiry in the URL, content type, and size per `mugshot_urls` key.**
- [x] **Step 4: Write the findings into `geni-api.md` and correct this plan's "(verify)" marks and the field list in Task 2.**

### Task 2: Photo model and parser

**Files:**
- Create: `geni/src/main/java/app/familygem/geni/api/GeniPhoto.kt`
- Test: `geni/src/test/java/app/familygem/geni/api/GeniPhotoTest.kt`

**Interfaces:**
- Produces: `data class GeniPhoto(val id: String, val title: String?, val contentType: String?, val urls: Map<String, String>, val updatedAt: String?, val albumId: String?)` where `urls` holds the size URLs only (the `url` key is not one); `fun urlFor(vararg preferred: String): String?` returning the URL of the first size name present (names: `thumb`, `small`, `medium`, `large`, `original`), else null; `fun parsePhoto(json: String): GeniPhoto` (a single photo or an upload response); `fun parsePhotos(json: String): List<GeniPhoto>` (`results` array, empty when missing); `fun mugshotToPhoto(profile: GeniProfile): GeniPhoto?` (main photo: `id` = `geniId(mugshotUrls["url"])`, `urls` = the other entries; null when `mugshotUrls` is null, empty or has no `url`).

- [x] **Step 1: Write failing tests** `parsesAlbumFixture`, `parsesAlbumFixture` (2 photos, ids `photo-2176`, `photo-2175`), `parsesUploadResponse`, `urlForPicksFirstPresentName`, `mugshotGivesPhotoIdFromUrlKey` (from `profile-mugshot.json`, id `photo-2175`), `emptyMugshotGivesNull`, `emptyResultsGivesEmptyList` using the Task 1 fixtures.
- [x] **Step 2: Run** `gradle :geni:testDebugUnitTest --tests '*GeniPhotoTest*'`; expect FAIL (undefined).
- [x] **Step 3: Implement the signatures above in `GeniPhoto.kt` with Gson and the `rawResults` style of `GeniModels.kt`.**
- [x] **Step 4: Run the same command; expect PASS.**

### Task 3: Client methods for photos

**Files:**
- Modify: `geni/src/main/java/app/familygem/geni/api/GeniClient.kt`
- Test: `geni/src/test/java/app/familygem/geni/api/GeniClientTest.kt`

**Interfaces:**
- Consumes: `GeniPhoto`, `parsePhotos` (Task 2), `GeniClient.post`, `getJson`.
- Produces on `GeniClient`: `suspend fun photos(profileId: String): List<GeniPhoto>` (all pages, stops on a page that adds nothing, like `managedGuids`); `suspend fun photo(photoId: String): GeniPhoto`; `suspend fun addPhoto(profileId: String, fileName: String, title: String?, bytes: ByteArray): GeniPhoto` (the first photo of a profile becomes its main one on Geni; no flag); `suspend fun deletePhoto(photoId: String)` (`POST photo-X/delete`). No `setMainPhoto`: Geni has no call for it.

- [x] **Step 1: Write failing tests with MockWebServer:** `photosReadsAllPages` (pages until `results` is empty or a page adds no new ID), `photosStopsWhenPageAddsNothing`, `addPhotoSendsBase64FileNameAndTitle` (assert form keys `file`, `file_name`, `title` and that base64 decodes to the bytes), `deletePhotoPostsToPhotoDelete`, `photosPath404ThrowsGeniApiException`.
- [x] **Step 2: Run the class; expect FAIL.**
- [x] **Step 3: Implement the methods; reuse `post` for uploads (form POST with base64), no new HTTP stack.**
- [x] **Step 4: Run the class; expect PASS.**

### Task 4: Photo downloader

**Files:**
- Create: `geni/src/main/java/app/familygem/geni/api/PhotoDownloader.kt`
- Test: `geni/src/test/java/app/familygem/geni/api/PhotoDownloaderTest.kt`

**Interfaces:**
- Produces: `class PhotoDownloader(http: OkHttpClient = default, maxBytes: Long = 15_000_000)` with `suspend fun download(url: String, target: java.io.File): String` returning the content type. Writes to `target.name + ".part"` in the same folder and renames on success; throws `GeniApiException` for non-2xx, `IllegalArgumentException` for non-https (except localhost in tests via an `allowHttp` constructor flag), `IOException` for too large or non-image content type. Photo URLs are signed and expire (about a day) and need no token.

- [x] **Step 1: Write failing tests:** `savesImageBytes`, `sendsNoAuthorizationHeader`, `rejectsHtmlContentType`, `stopsAtSizeCapAndLeavesNoFile`, `failedDownloadKeepsExistingTarget`, `refusesRedirects`.
- [x] **Step 2: Run the class; expect FAIL.**
- [x] **Step 3: Implement with its own OkHttp client (no redirects, no auth interceptor), streaming copy that counts bytes; delete the `.part` file on any failure.**
- [x] **Step 4: Run the class and the whole `:geni` unit suite; expect PASS.**
- [ ] **Step 5: Tick Task 1–4 here, add the PR's roadmap line, open PR 1 (ask the user before commit and push).**

## PR 2: Main photo, read (Tasks 5–8)

Detailed steps are written when PR 1 is merged, from its findings. Deliverables:

### Task 5: `photo_links` table and Room migration
- Create `PhotoLinkEntity` and `PhotoLinkDao` in `geni/.../sync` next to the other Room classes; add to `SyncStore` and `RoomSyncStore` (`photoLinks(treeId)`, `savePhotoLink`, `deletePhotoLink`); migration test.

### Task 6: Mapping the main photo into the tree
- Note from the PR 1 review: `mugshotToPhoto` has no `updatedAt` and signed URLs change daily, so detect a changed photo by photo ID (or the URL path without its query), never by the full URL. Check the downloaded bytes are an image (magic bytes or decode) before creating the media entry. Download one photo per target file at a time (the `.part` name is shared).
- `PhotoSync` in `geni/.../sync/PhotoSync.kt`: given a loaded profile, compare its `mugshotToPhoto` with the link (by photo ID, see the note above), download when new or changed, create or replace the `OBJE` through `LocalTree`, set `_GENI_PHOTO_ID`; remove it when Geni has no photo and the link is not pending.
- Tests with `FakeTreeHost` and a fake downloader: new photo, unchanged photo not re-downloaded, changed photo replaced, photo gone, failed download keeps the old file.

### Task 7: Loader and refresh hooks
- Call `PhotoSync` from `GeniLoader.load` after the merge save, off the main thread, cancellable; errors logged, never failing the person load.

### Task 8: Show in the app and phone check
- Confirm the card and diagram show the linked `OBJE` through upstream's existing media code (no upstream edit unless needed); check on the Pixel 6 with the sandbox; roadmap line; PR 2.

## PR 3: Album, read (Tasks 9–11)

- Task 9: `AlbumLoader` (fetch `client.photos`, diff with `photo_links`, download chosen photos, link them).
- Task 10: album entry on the person card of a Geni person (hook in upstream's profile media tab; list, tap to download, progress, failure message).
- Task 11: "Keep offline" option "include photos" (setting, off by default) and PR 3.

## PR 4: Upload (Tasks 12–15; expanded 2026-11-20)

Decisions (from reading the write path): no Room migration: `JobKind` is stored as text, the photo job keeps its file name and title in `params` (`file`, `title`), the owner in `personGedcomId` and `geniId` (profile). Job key `photo:<personGedcomId>:<fileName>`. An `ADD_PHOTO` job behaves like an add in the queue (it may exist on Geni after a lost response, so it is never retried by itself: `SENDING` becomes `FAILED`, failed stays failed until the user retries). Local-only switch: one app-wide setting "Send new photos to Geni" (default on); when off, no photo jobs are planned and pending ones are dropped by the planner. The first photo becoming the main one on Geni is shown as a note next to the switch; the local main flag follows on the next load through `PhotoSync`. Only inline media (`Person.media`) with a plain file name, no `_GENI_PHOTO_ID` tag and no photo link is a candidate.

### Task 12: Local media in the saved tree and photo jobs in the planner

**Files:**
- Modify: `geni/src/main/java/app/familygem/geni/sync/UploadJob.kt` (add `ADD_PHOTO`), `LocalTree.kt`, `UploadPlanner.kt`
- Test: `geni/src/test/java/app/familygem/geni/sync/UploadPlannerTest.kt`

**Interfaces:**
- Produces: `JobKind.ADD_PHOTO`; `data class LocalMedia(val file: String, val title: String?, val geniPhotoId: String?)`; `LocalPerson.media: List<LocalMedia> = emptyList()` (filled by `LocalTree.capture` from `Person.media`: file name = last path segment, `geniPhotoId` = tag `PhotoSync.PHOTO_TAG`); `UploadPlanner.plan(treeId, local, links, jobs, photoLinks: List<PhotoLink> = emptyList(), uploadPhotos: Boolean = true)`.

- [ ] **Step 1: Failing tests in `UploadPlannerTest`:** `newMediaOnLinkedPersonPlansPhotoJob` (job key `photo:I1:me.jpg`, kind `ADD_PHOTO`, geniId = profile id, params `[file to me.jpg, title to Me]`); `taggedMediaIsNotPlanned`; `mediaWithPhotoLinkIsNotPlanned` (photo link for that profile with `fileName = "me.jpg"`); `photosOffPlansNothingAndDropsPendingJob`; `photoJobDroppedWhenMediaGone`; `mediaOfUnlinkedPersonIsNotPlanned`.
- [ ] **Step 2: Run:** `:geni:testDebugUnitTest --tests '*UploadPlannerTest'`; expect compile failure, then FAIL.
- [ ] **Step 3: Implement.** In the planner loop over linked people, for each candidate media (no `geniPhotoId`, `file` not among `photoLinks.filter{geniProfileId==link.geniId}.fileName`, and `uploadPhotos`) add a job with `localHash = media.hashCode()`; collect its key in a `photoKeys` set; in the final job loop treat `job.key.startsWith("photo:")` as gone when its key is not in `photoKeys`. Make jobs stay in `UploadJob` field order used by `sameChange`.
- [ ] **Step 4: Run all `:geni:testDebugUnitTest`; expect PASS.**
- [ ] **Step 5: Commit** "Plan photo upload jobs for new media of Geni people".

### Task 13: Uploader and queue send photos

**Files:**
- Modify: `geni/src/main/java/app/familygem/geni/sync/Uploader.kt`, `SyncQueue.kt`
- Test: `geni/src/test/java/app/familygem/geni/sync/UploaderTest.kt`, `SyncQueueTest.kt`

**Interfaces:**
- Consumes: Task 12's `ADD_PHOTO`; `GeniClient.addPhoto(profileId, fileName, title, bytes): GeniPhoto`; `TreeHost.mediaDir(treeId)`; `PhotoSync.PHOTO_TAG`, `PhotoSync.marker(photo)`.
- Produces: `Uploader(client, store, host, now, prepare: suspend (File) -> ByteArray = { it.readBytes() })`; `Uploader.run` handles `ADD_PHOTO`; `SyncQueue` treats `ADD_PHOTO` like an add (rename `isAdd` to cover it).

- [ ] **Step 1: Failing tests in `UploaderTest`:** `photoUploadStoresLinkAndTagsMedia` (file in the fake media dir; after `run` the store has a `PhotoLink` with the returned photo id, `PhotoState.DOWNLOADED`, `fileName` = the file, `isMain = false`; the media carries tag `_GENI_PHOTO_ID`; outcome Done); `photoAlreadyLinkedIsNotUploadedAgain` (link with the same `fileName` → no POST, Done); `missingFileFails` (Failed, no POST); `lostResponseFailsAndNeverRetriesItself` (exception after POST → `Failed` mentioning "check on Geni"); `networkDownBeforeSendRetries` (UnknownHostException → Retry); `prepareIsUsedForBytes`.
- [ ] **Step 2: Failing tests in `SyncQueueTest`:** `interruptedPhotoUploadBecomesFailed`; `donePhotoJobIsDeleted`.
- [ ] **Step 3: Implement `private suspend fun addPhoto(job: UploadJob): Outcome`:** same error mapping as `add` after the POST (reuse its catch structure through a small shared helper if it stays readable); save the `PhotoLink` first, then tag the media with `host.edit` (best effort, like add tags), then `host.refreshScreens()`. `SyncQueue`: extend the add check to `ADD_PHOTO`.
- [ ] **Step 4: Run `:geni:testDebugUnitTest`; expect PASS.**
- [ ] **Step 5: Commit** "Upload new photos of Geni people".

### Task 14: App wiring, scaling and the switch

**Files:**
- Modify: `geni/src/main/java/app/familygem/geni/Geni.kt` (pass `prepare` and the setting), `geni/src/main/java/app/familygem/geni/ui/GeniLoginActivity.kt` and its layout (switch and note), `geni/src/main/res/values/geni_strings.xml`, `values-ru/geni_strings.xml`
- Create: `geni/src/main/java/app/familygem/geni/sync/PhotoScaler.kt`
- Test: `geni/src/androidTest/java/app/familygem/geni/sync/PhotoScalerTest.kt` (needs `BitmapFactory`)

**Interfaces:**
- Produces: `object PhotoScaler { suspend fun scaled(file: File, maxSide: Int = 2048): ByteArray }` (decodes with `inSampleSize`, rescales so the longer side is at most `maxSide`, JPEG quality 85; a file already small enough and JPEG is returned unchanged); a `GeniSettings`-style boolean `uploadPhotos` in the existing Geni preferences (default true) read when the queue plans (`SyncQueue` gets `uploadPhotos: () -> Boolean`).

- [ ] **Step 1: Instrumented test `PhotoScalerTest`:** a 4000x3000 bitmap becomes at most 2048 on the long side and decodes; a 100x100 JPEG comes back byte-identical.
- [ ] **Step 2: Implement `PhotoScaler`, wire `prepare = PhotoScaler::scaled`, add the switch "Send new photos to Geni" with the note "The first photo of a person becomes their main photo on Geni." in the account screen; turning the switch back on plans photos added meanwhile.**
- [ ] **Step 3: Build:** `:geni:testDebugUnitTest :app:assembleDebug` (in the rsync copy); expect PASS. Run `connectedDebugAndroidTest` for the new test if the phone is attached.
- [ ] **Step 4: Commit** "Scale photos before upload and add the send-photos switch".

### Task 15: Phone check, roadmap, PR 4

- [ ] **Step 1: On the phone (sandbox, logged in): add a photo to a Geni person (the person has no photo): it uploads, appears on Geni as main, the card keeps one copy; add a second: it uploads as album photo; switch off, add a third: stays local; airplane mode: waits then uploads after reconnect.**
- [ ] **Step 2: Tick roadmap PR 4, deferred list, push, open PR into `geni`, CodeRabbit loop as for PR 3, Progress-log line before merge.**

## PR 5: Delete and deletions from Geni (Tasks 16–18; expanded 2026-11-20)

Decisions (from the code as it is after PR 4):
- Main-photo change on Geni is already handled (PR 3: the old main stays as an album photo), so the old Task 18 shrinks to docs.
- **Deleting a Geni photo in the app.** A hook at the start of Delete in the person's media tab (`MediaFragment`, menu id 8) hands inline media tagged `_GENI_PHOTO_ID` to `GeniGlue.handleDeleteMedia`. Non-main photo: choices "Remove from this device" and, if the profile is managed (stage 5 `DeleteRules.isManaged`), "Delete on Geni" (second confirmation). The main photo can be deleted only on Geni (managed profile); otherwise a message explains that it comes from Geni and would return at the next refresh. Media not from Geni: upstream as usual.
- **Delete on Geni** removes the photo locally at once (media entry, photo link, file if unused) and queues `DELETE_PHOTO` (key `delphoto:<photoId>`, `geniId` = photo ID, `personGedcomId` = owner). Deleting is safe to repeat: a 404 counts as done, a server error retries.
- **Photos deleted on Geni** (album photos): when the album is read (album dialog, "Download all", Keep offline), downloaded non-main photos missing from Geni's list are removed from the device, unless the media has local data (a title, a note or a source): then it stays untouched (stale link) and the case is deferred in the roadmap. The main photo is already handled by `PhotoSync.sync`.
- No conflict list entry in this PR (ruled out above: modified media are left alone).

### Task 16: `DELETE_PHOTO` job, local removal and the queue

**Files:**
- Modify: `geni/src/main/java/app/familygem/geni/sync/UploadJob.kt`, `PhotoSync.kt`, `Uploader.kt`, `SyncQueue.kt`, `UploadPlanner.kt`
- Test: `PhotoSyncTest.kt`, `UploaderTest.kt`, `SyncQueueTest.kt`, `UploadPlannerTest.kt`

**Interfaces:**
- Produces: `JobKind.DELETE_PHOTO`; `PhotoSync.removeLocal(treeId: Int, geniProfileId: String, personGedcomId: String, geniPhotoId: String): Boolean` (removes the media tagged with that photo ID from the person, the photo link, and the file when no other link uses it; true if the tree changed); `SyncQueue.enqueueDeletePhoto(treeId: Int, photoId: String, personGedcomId: String, label: String)` (once per photo; a failed or dismissed one is asked for again, like `enqueueDelete`); `Uploader.run` handles `DELETE_PHOTO` by `client.deletePhoto(job.geniId)`; the planner never deletes a `delphoto:` job as "gone" (like `DELETE_PROFILE`).

- [ ] **Step 1: Failing tests:** `PhotoSyncTest.removeLocalDropsMediaLinkAndFile` (and keeps a file another profile's link uses); `UploaderTest.deletePhotoPostsDeleteAndIsDone`, `deletePhotoNotFoundIsDone`, `deletePhotoServerErrorRetries`, `deletePhotoClientErrorFails`; `SyncQueueTest.enqueueDeletePhotoQueuesOnceAndRerequestFailed`; `UploadPlannerTest.deletePhotoJobIsNeverDroppedAsGone`.
- [ ] **Step 2: Implement** the interfaces above; `DELETE_PHOTO` is not an add (a repeat is safe); `removeLocal` reuses the private removal code of `PhotoSync`.
- [ ] **Step 3: Run `:geni:testDebugUnitTest` (in the rsync copy); expect PASS.**
- [ ] **Step 4: Commit** "Queue deletion of Geni photos and remove them locally".

### Task 17: Photos deleted on Geni leave the device

**Files:**
- Modify: `geni/src/main/java/app/familygem/geni/sync/PhotoSync.kt`, `AlbumLoader.kt`; callers in `app/src/geni/java/app/familygem/geni/GeniAlbum.kt` and `GeniPrefetch.kt`
- Test: `PhotoSyncTest.kt`, `AlbumLoaderTest.kt`

**Interfaces:**
- Produces: `PhotoSync.reconcileAlbum(treeId: Int, geniProfileId: String, personGedcomId: String, album: List<GeniPhoto>): Int` (removes downloaded non-main photos of the profile whose ID is not in `album` and whose media has no title, notes or sources; returns how many); `AlbumLoader.list(treeId, geniProfileId, personGedcomId)` calls it after fetching the album and returns `Album(entries: List<AlbumEntry>, removed: Int)`; `downloadAll` reconciles too. `GeniAlbum` calls its `onChanged` when `removed > 0` and shows "N photos removed (deleted on Geni)".

- [ ] **Step 1: Failing tests:** `reconcileRemovesAlbumPhotoMissingOnGeni`, `reconcileKeepsMainPhoto`, `reconcileKeepsPhotoWithLocalTitleNote Or Source` (media with a note stays, link stays), `reconcileKeepsPhotosStillInAlbum`, `reconcileIgnoresOtherProfiles`; `AlbumLoaderTest.listReportsRemovedPhotos`.
- [ ] **Step 2: Implement; update the callers and strings (EN and RU).**
- [ ] **Step 3: Run `:geni:testDebugUnitTest :app:assembleDebug`; expect PASS.**
- [ ] **Step 4: Commit** "Remove album photos that were deleted on Geni".

### Task 18: Delete hook, dialogs, docs and PR 5

**Files:**
- Modify: `app/src/main/java/app/familygem/profile/MediaFragment.kt` (one GENI-HOOK line at the start of case 8), `app/src/geni/java/app/familygem/geni/GeniGlue.kt` (`handleDeleteMedia`), `geni/UPSTREAM-HOOKS.md`, strings EN and RU, `docs/superpowers/plans/README.md`, memory file

**Interfaces:**
- Produces: `GeniGlue.handleDeleteMedia(context: Context, person: Person, media: Media, onDone: () -> Unit): Boolean` (true when it took over, as `handleDelete` does for persons).

- [ ] **Step 1: Implement `handleDeleteMedia` per the Decisions; dialog texts: device removal "The photo stays on Geni and can be downloaded again from the album.", Geni deletion "This photo will be deleted on Geni for everyone. This cannot be undone.", main photo "This is the main photo from Geni. Delete it on Geni to remove it." (RU strings too).**
- [ ] **Step 2: Hook line and `UPSTREAM-HOOKS.md` row; build `:geni:testDebugUnitTest :app:assembleDebug`; expect PASS.**
- [ ] **Step 3: Phone check (sandbox, driven with uiautomator and the sandbox tool): remove an album photo from the device (album shows it downloadable again); delete the main photo → message; delete a photo on Geni (managed profile) → gone from Geni; delete a photo on Geni with the tool, open the album → removed from the device.**
- [ ] **Step 4: Roadmap: tick PR 5 and stage 6, deferred list, Progress-log line before merge; push, PR, CodeRabbit loop (sonnet), merge, memory update.**

## Review Focus (PR 5)

- Photo deleted on Geni while it is the person's only local media with a note: not removed (test in Task 17).
- Delete on Geni fails offline: the photo is already gone locally; the job waits and the failed list shows the photo ID label.
- Two profiles sharing one file: the file stays until the last link goes (Task 16 test).
- The main photo removed on Geni and then the album read: handled once, no double removal.

## Self-review notes

- Spec coverage: read main, album, offline, upload, delete (set main is impossible on Geni), conflicts, local-only rule, token rule: all have tasks. "No second copy of unchanged photos" is tested in Task 6.
- PR 2–5 are outlines on purpose: their exact signatures depend on Task 1's findings and on the Room schema as it stands after PR 1; they are expanded into steps before each PR starts.
