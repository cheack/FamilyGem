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
- Endpoint names and limits below marked "(verify)" are settled in Task 1 and the plan is corrected before Task 2.

## Review Focus

- Photo URL that expires or needs a token: download fails with a clear error and the main photo keeps its old local file.
- A downloaded file that is not an image (HTML error page): rejected, not saved as media.
- Very large photo (tens of MB): download stops at the size cap; upload is scaled down first.
- Profile with no photo (`mugshot_urls` null or empty): no media is created and an old Geni-linked photo is removed only when Geni says it is gone.
- Same photo uploaded twice after a failed response: not duplicated (job state kept as for profile adds).

## PR 1: API client (Tasks 1–4)

### Task 1: Sandbox check of the photo endpoints

**Files:**
- Modify: `app/src/androidTest/java/app/familygem/geni/GeniSandboxTool.kt` (new commands `photos`, `photo`)
- Create: `geni/src/test/resources/geni/photo-list.json`, `photo-single.json`, `photo-upload-response.json` (real responses with names and IDs shortened)
- Modify: memory `geni-api.md`

**Interfaces:**
- Produces: the settled endpoint names, field names, photo sizes, upload size limit, whether URLs need a token or expire, and the delete and set-main calls. Later tasks use exactly these.

- [ ] **Step 1: Using the existing dev tool on the phone (user logged in on the sandbox), request `profile-X/photos`, `photo-Y`, and `profile-X?fields=mugshot_urls`; save the raw JSON.**
- [ ] **Step 2: Try the calls for: upload to album without setting main (`add-photo` flag or other call), set main, delete (`photo-Y/delete`) on a throwaway profile; record each result and HTTP code.**
- [ ] **Step 3: Fetch a photo URL once without and once with the token header; note host, expiry in the URL, content type, and size per `mugshot_urls` key.**
- [ ] **Step 4: Write the findings into `geni-api.md` and correct this plan's "(verify)" marks and the field list in Task 2.**

### Task 2: Photo model and parser

**Files:**
- Create: `geni/src/main/java/app/familygem/geni/api/GeniPhoto.kt`
- Test: `geni/src/test/java/app/familygem/geni/api/GeniPhotoTest.kt`

**Interfaces:**
- Produces: `data class GeniPhoto(val id: String, val title: String?, val contentType: String?, val urls: Map<String, String>, val updatedAt: String?)` with `fun bestUrl(maxSide: Int): String?` returning the largest URL not above that size, else the smallest; `fun parsePhotos(json: String): List<GeniPhoto>`; `fun mugshotToPhoto(profile: GeniProfile): GeniPhoto?` (main photo as a photo, `id` from the URL's photo ID; null if none). Final fields follow Task 1.

- [ ] **Step 1: Write failing tests** `parsesAlbumFixture`, `bestUrlPicksLargestWithinLimit`, `bestUrlFallsBackToSmallest`, `emptyMugshotGivesNull` using the Task 1 fixtures.
- [ ] **Step 2: Run** `gradle :geni:testDebugUnitTest --tests '*GeniPhotoTest*'`; expect FAIL (undefined).
- [ ] **Step 3: Implement the signatures above in `GeniPhoto.kt` with Gson and the `rawResults` style of `GeniModels.kt`.**
- [ ] **Step 4: Run the same command; expect PASS.**

### Task 3: Client methods for photos

**Files:**
- Modify: `geni/src/main/java/app/familygem/geni/api/GeniClient.kt`
- Test: `geni/src/test/java/app/familygem/geni/api/GeniClientTest.kt`

**Interfaces:**
- Consumes: `GeniPhoto`, `parsePhotos` (Task 2), `GeniClient.post`, `getJson`.
- Produces on `GeniClient`: `suspend fun photos(profileId: String): List<GeniPhoto>` (all pages, stops on a page that adds nothing, like `managedGuids`); `suspend fun photo(photoId: String): GeniPhoto`; `suspend fun addPhoto(profileId: String, fileName: String, title: String?, bytes: ByteArray, makeMain: Boolean): GeniPhoto`; `suspend fun deletePhoto(photoId: String)`; `suspend fun setMainPhoto(photoId: String)` only if Task 1 found a call (verify).

- [ ] **Step 1: Write failing tests with MockWebServer:** `photosReadsAllPages`, `photosStopsWhenPageAddsNothing`, `addPhotoSendsBase64FileNameAndTitle` (assert form keys `file`, `file_name`, `title` and that base64 decodes to the bytes), `deletePhotoPostsToPhotoDelete`, `photosPath404ThrowsGeniApiException`.
- [ ] **Step 2: Run the class; expect FAIL.**
- [ ] **Step 3: Implement the methods; reuse `post` for uploads (form POST with base64), no new HTTP stack.**
- [ ] **Step 4: Run the class; expect PASS.**

### Task 4: Photo downloader

**Files:**
- Create: `geni/src/main/java/app/familygem/geni/api/PhotoDownloader.kt`
- Test: `geni/src/test/java/app/familygem/geni/api/PhotoDownloaderTest.kt`

**Interfaces:**
- Produces: `class PhotoDownloader(http: OkHttpClient = default, maxBytes: Long = 15_000_000)` with `suspend fun download(url: String, target: java.io.File): String` returning the content type. Writes to `target.name + ".part"` in the same folder and renames on success; throws `GeniApiException` for non-2xx, `IllegalArgumentException` for non-https (except localhost in tests via an `allowHttp` constructor flag), `IOException` for too large or non-image content type.

- [ ] **Step 1: Write failing tests:** `savesImageBytes`, `sendsNoAuthorizationHeader`, `rejectsHtmlContentType`, `stopsAtSizeCapAndLeavesNoFile`, `failedDownloadKeepsExistingTarget`, `refusesRedirects`.
- [ ] **Step 2: Run the class; expect FAIL.**
- [ ] **Step 3: Implement with its own OkHttp client (no redirects, no auth interceptor), streaming copy that counts bytes; delete the `.part` file on any failure.**
- [ ] **Step 4: Run the class and the whole `:geni` unit suite; expect PASS.**
- [ ] **Step 5: Tick Task 1–4 here, add the PR's roadmap line, open PR 1 (ask the user before commit and push).**

## PR 2: Main photo, read (Tasks 5–8)

Detailed steps are written when PR 1 is merged, from its findings. Deliverables:

### Task 5: `photo_links` table and Room migration
- Create `PhotoLinkEntity` and `PhotoLinkDao` in `geni/.../sync` next to the other Room classes; add to `SyncStore` and `RoomSyncStore` (`photoLinks(treeId)`, `savePhotoLink`, `deletePhotoLink`); migration test.

### Task 6: Mapping the main photo into the tree
- `PhotoSync` in `geni/.../sync/PhotoSync.kt`: given a loaded profile, compare its `mugshotToPhoto` with the link (by photo ID and `updatedAt`), download when new or changed, create or replace the `OBJE` through `LocalTree`, set `_GENI_PHOTO_ID`; remove it when Geni has no photo and the link is not pending.
- Tests with `FakeTreeHost` and a fake downloader: new photo, unchanged photo not re-downloaded, changed photo replaced, photo gone, failed download keeps the old file.

### Task 7: Loader and refresh hooks
- Call `PhotoSync` from `GeniLoader.load` after the merge save, off the main thread, cancellable; errors logged, never failing the person load.

### Task 8: Show in the app and phone check
- Confirm the card and diagram show the linked `OBJE` through upstream's existing media code (no upstream edit unless needed); check on the Pixel 6 with the sandbox; roadmap line; PR 2.

## PR 3: Album, read (Tasks 9–11)

- Task 9: `AlbumLoader` (fetch `client.photos`, diff with `photo_links`, download chosen photos, link them).
- Task 10: album entry on the person card of a Geni person (hook in upstream's profile media tab; list, tap to download, progress, failure message).
- Task 11: "Keep offline" option "include photos" (setting, off by default) and PR 3.

## PR 4: Upload (Tasks 12–15)

- Task 12: `ADD_PHOTO` and `SET_MAIN_PHOTO` job types in `UploadJob` and the Room queue (migration).
- Task 13: `UploadPlanner` finds new `OBJE` on Geni persons without a link and queues `ADD_PHOTO` (default upload, local-only switch); scales the image before upload.
- Task 14: `Uploader` runs the jobs, writes the returned photo ID into `photo_links`, handles the "response lost" case without duplicates.
- Task 15: UI: switch to keep a photo local, "Use as main photo"; failed uploads appear in the account screen list; PR 4.

## PR 5: Delete and conflicts (Tasks 16–18)

- Task 16: `DELETE_PHOTO` job, managed profiles only, confirmation dialog text, reuse of stage 5 rules.
- Task 17: photos deleted on Geni leave the device (extend `PhotoSync` and `AlbumLoader`); local edits to a deleted photo go to the conflict list.
- Task 18: main photo changed on both sides (Geni wins, local file stays in the album); roadmap, README progress log, memory update; PR 5.

## Self-review notes

- Spec coverage: read main, album, offline, upload, set main, delete, conflicts, local-only rule, token rule: all have tasks. "No second copy of unchanged photos" is tested in Task 6.
- PR 2–5 are outlines on purpose: their exact signatures depend on Task 1's findings and on the Room schema as it stands after PR 1; they are expanded into steps before each PR starts.
