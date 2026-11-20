# Geni sync roadmap

Design: [2026-11-16-geni-sync-design.md](../specs/2026-11-16-geni-sync-design.md).
Each stage gets its own detailed plan with a step checklist; steps are ticked (`- [x]`) as they are done and committed.
The items under each stage are the deliverables; the stage's plan breaks them into tested steps.
See the [progress log](#progress-log) at the end for what landed when.

## 1. API client and login

Plan: [2026-11-16-geni-1-api-login.md](2026-11-16-geni-1-api-login.md)

- [x] Config from `geni.properties` (`geniAppId`, `geniHost`, `geniRedirectUri`); no client secret in the app
- [x] Rate limiter driven by `X-API-Rate-Limit` / `X-API-Rate-Remaining` / `X-API-Rate-Window`
- [x] OAuth mobile flow: authorize URL and redirect parsing (token, denial)
- [x] Encrypted token store; API client with bearer token, 401 and 429 handling
- [x] Login screen and "Geni account" item in the trees menu (hook in `TreesActivity`)
- [x] Manual check on the sandbox: login, cancel, redirect scheme, real token lifetime

### Deferred from the stage 1 review

- [ ] Make OAuth `state` mandatory once it is confirmed that Geni echoes it
- [ ] "Session expired, log in again" message after 401 or expiry; clear an expired token from the store
- [ ] Show `geni_login_failed` and the code instead of the raw Geni error body
- [ ] Handle no browser (`ActivityNotFoundException`) on login
- [ ] Fall back to the profile ID when the name is empty
- [ ] Keep the status message on rotation; avoid a second profile request on rotation
- [ ] Log out also calls `/platform/oauth/invalidate_token`
- [ ] Token store off the main thread; thread-safe `Geni.client` cache; cancellable HTTP calls
- [ ] Lower-case the redirect scheme; escape `geni.properties` values in `buildConfigField`
- [ ] Tests: 429 waits a window, 429 then 429 fails, `X-API-Rate-Remaining: 0` delays the next call
- [ ] Check on the sandbox: token alphabet (`+`), HTTP code for a revoked token

## 2. Read path

Plan: [2026-11-16-geni-2-read-path.md](2026-11-16-geni-2-read-path.md)

- [x] Sandbox exploration: real responses of `profile`, `immediate-family`, `union`; dates and locations format
- [ ] Show Geni profile photos (`mugshot_urls`) in FamilyGem media; upload new photos (`profile-X/add-photo`)
- [x] Room database in `:geni`: Geni tree marker, ID mapping (GEDCOM ID ↔ Geni ID), snapshots
- [x] Mapping Geni → GEDCOM: profile → `INDI`, union → `FAM`, `_GENI_ID`, standard fields, `_GENI_*` for the rest
- [x] Create a Geni tree from the logged-in user's profile (entry in the trees list, root = own profile)
- [x] Lazy load: opening a person fetches `immediate-family` and merges it into the cache (hook in person card or diagram)
- [x] Edge people marked as not fully loaded; opening them triggers a fetch
- [x] Background prefetch within N steps of the root (N = 3, setting), WorkManager
- [x] "Keep offline" action: download a person's branch to a chosen depth
- [x] Measure size and time of a radius-3 prefetch and of saving a large cache JSON

### Deferred from the stage 2 review

- [ ] Read and save links inside the tree lock, or merge flags, so overlapping loads do not reset `fullyLoaded`/`loadedAtMillis`
- [ ] "Keep offline" only for Geni trees and when logged in
- [ ] Partners with a known gender first, so an unknown-gender partner cannot cause two `HUSB`
- [ ] Catch Room errors in the person-open path and in `GeniLoader.load`
- [ ] `NonCancellable` around save and links after the merge block
- [ ] Thread-safe `Geni.client`; `treeExists` on the main thread
- [ ] Shared file lock with upstream saves of `N.json`
- [ ] Create the Geni tree outside the login screen's scope, so leaving the screen cannot leave a half-made tree
- [ ] `refreshData` (generation count) off the main thread for large trees; measure a tree of about 5000 people

## 3. Write path

Plan: [2026-11-17-geni-3-write-path.md](2026-11-17-geni-3-write-path.md)

- [x] Hook in `TreeUtil.saveJson`: signal that a Geni tree was written
- [x] Diff of the whole tree against snapshots, with a hash per record
- [x] Upload queue in Room; job types: update profile, add child/parent/partner (a sibling is a child of the parents' union), update union
- [x] Mapping GEDCOM → Geni for uploads; local-only data (sources, notes, extra events) never uploaded
- [x] People created offline: later jobs refer to them by GEDCOM ID, resolved to the Geni ID when they run
- [x] Queue runner: order, rate limit, retries with back-off (in-process, not WorkManager: upstream cancels all work on save)
- [x] Permanent failures kept and shown to the user (list of failed changes)

### Deferred from the review of PR 1 (Tasks 1–4)

- [ ] Real round-trip test: upload params → simulated Geni profile → `ofSnapshot` equals the local projection
- [x] `FamilyMerger`: clear `verifiedHash` when the snapshot changes (CodeRabbit, PR 1)
- [ ] `parseDate`: reject days that do not exist in the month (31 FEB)
- [ ] Name text after the closing slash ("Ivan /Petrov/ Jr") is invisible to sync

### Deferred from the review of PR 2 (Tasks 5–7)

- [ ] A new person in two families is added through one; the other relation stays local without notice (Geni has no call to link existing profiles): show it to the user
- [x] `Uploader.refreshSnapshot` read-modify-write can overwrite a loader's link write made at the same time (`fullyLoaded`, `loadedAtMillis`)
- [x] A load of a profile added without a snapshot applies every Geni field (`FamilyMerger` with `previous = null`) and can overwrite a local edit not uploaded yet (CodeRabbit, PR 2)
- [ ] A person deleted during the add request whose GEDCOM ID is reused at once gets the new link and tag (CodeRabbit, PR 2)
- [ ] When the tag write after an add fails and the person is renamed, the record is ambiguous and its edits are not uploaded: show it to the user (CodeRabbit, PR 2)

### Found in the sandbox check (Task 10)

- [ ] No check with a 4xx from Geni on the phone (the sandbox tree has no profile the user cannot edit); covered by unit tests only
- [ ] The network watch resumes uploads as soon as Android reports a network; DNS may not be ready yet, so the first try can still back off

### Deferred from the review of stage 4 PR 1

- [ ] A death or divorce cleared on Geni removes the local fact with its notes, sources and media (an emptied DEAT or DIV would still mean dead or divorced); other events and occupation are emptied instead
- [ ] Links without a snapshot made by builds before stage 4 (only a hash of what was sent) keep the fill-empty-fields rule on loads: a field cleared locally after such an add comes back from Geni. New builds store what was sent as the snapshot instead

Moved out of stage 3: photo upload goes with "Show Geni profile photos" (stage 2); delete profile goes with the delete rules (stage 5).

## 4. Refresh and conflicts

Plan: [2026-11-17-geni-4-refresh-conflicts.md](2026-11-17-geni-4-refresh-conflicts.md)

- [x] Refresh on open: fetch current Geni version, update cache and snapshot when nothing is pending
- [x] Daily refresh of radius N on an unmetered network
- [x] Refresh updates only Geni-mapped fields; local-only data is kept
- [x] Conflict check before upload using `updated_at`; paused job when someone else changed the profile
- [x] Conflict screen: both versions, choice per field (based on `share/CompareActivity`)
- [x] Merged profiles (`merged_into`): remap to the surviving ID
- [x] Deleted or hidden profiles: removed from the cache unless they have pending changes; otherwise the user decides

### Deferred from the review of PR 6 (Tasks 8–10)

- [ ] A merged profile answered only under the survivor's ID (the sandbox answers under the requested ID with `merged_into`)
- [ ] Survivor cached by another load between the check and the move: two people with one Geni ID
- [ ] A person kept as local after "deleted on Geni" can be relinked by a later load that returns the old profile
- [ ] Resume a "deleted on Geni" resolution or a profile move interrupted between the tree save and the link writes
- [ ] A deleted profile whose person has only local data (no upload job): offer the keep-or-remove choice too
- [ ] With Auto Save off, a removal or move changes links before the tree is saved; reconcile if the user discards it
- [ ] Daily refresh while the app stays open for days on the same Wi-Fi: add a time-based trigger
- [ ] A Wi-Fi trigger that arrives while the tree's daily pass is still finishing is dropped until the next trigger
- [ ] Daily refresh time kept per tree ID: clear it when a tree is deleted, and do not save it if the root or depth changed during the pass
- [ ] A family emptied by a removal keeps marriage or divorce edits not uploaded yet (compare with its union snapshot)

## 5. Upstream UI rules

Plan: [2026-11-18-geni-5-upstream-ui.md](2026-11-18-geni-5-upstream-ui.md)

- [x] Hide merge, shared-tree import and sharing for Geni trees
- [x] Delete person in a Geni tree: only managed profiles, with explicit confirmation, then `profile-X/delete`; otherwise blocked
- [x] "Remove from this device" action (cache only; in the delete dialog)
- [x] Block unlinking a spouse or child in Geni trees (no API for it)
- [x] Backup restore of a Geni tree: a restored backup is a new local tree (new tree ID), so nothing to reset
- [x] GEDCOM export stays allowed

### Deferred from stage 5

- [ ] A person removed from this device returns as an edge person when a relative is loaded (a removed-marker would need a Room migration)
- [ ] "Remove from this device" and the not-managed / offline dialogs are covered by unit tests only, not checked on the phone
- [ ] An edit of a profile made after "Delete on Geni" was queued is not dropped from the queue (only an update already waiting is)
- [ ] `GeniGlue` reads the Geni tree IDs with a blocking Room read on the first hook (main thread), and a failed read leaves hooks treating Geni trees as local until the next hook; use an explicit unknown state
- [ ] A delete queued under one Geni account can be sent under another after a login change: record the confirming account on the job
- [ ] A family refresh started before a delete can restore the profile after the local cleanup: keep a record of confirmed deletions for the loader
- [ ] Local removal after Geni confirmed a delete is best effort: a failure leaves the person on the device with the link dropped; persist a cleanup-only phase
- [ ] With Auto Save off, "Remove from this device" drops links and jobs before the tree is saved; revert brings the person back unlinked
- [ ] `onTreeDeleted` clears Room rows asynchronously: a tree created with a reused ID before it runs loses its rows
- [ ] A concurrent reload after a GEDCOM ID edit can have its new link deleted by the removal (delete the link only if it is still the one read)
- [ ] A removal with Auto Save on does not check that the file write succeeded before dropping links
- [ ] The save hook of a removal can start the worker before a queued delete on Geni is cancelled
- [ ] `enqueueDelete` and the removal of the waiting edit are not one transaction
- [ ] Backup restore and GEDCOM export of a Geni tree not re-checked on the phone (by design they touch no Geni data)

## 6. Photos

Plan: [2026-11-18-geni-6-photos.md](2026-11-18-geni-6-photos.md). Design: [2026-11-18-geni-photos-design.md](../specs/2026-11-18-geni-photos-design.md). Replaces the stage 2 photo item.

- [x] PR 1: photo API client (list, get, upload, delete, download) and sandbox check
- [x] PR 2: main photo read: shown on card and diagram, kept current on refresh (phone-checked on sandbox)
- [x] PR 3: album read: browse, on-demand download, "Keep offline" option (phone-checked on sandbox)
- [ ] PR 4: upload: add photo (first one becomes main), offline queue and failure list
- [ ] PR 5: delete on Geni (managed profiles), deletions from Geni, conflicts

### Deferred from the review of photos PR 1

- [ ] `PhotoDownloader`: the derived client inherits the supplied client's cache and cookie jar; set `cache(null)` and `CookieJar.NO_COOKIES` (with a test that no `Cookie` header is sent)
- [ ] `PhotoDownloader` accepts any https host; consider allowing only Geni photo hosts
- [x] Check downloaded bytes are an image (magic bytes or decode) before creating the media entry (done in PR 2)

### Deferred from the review of photos PR 2

- [ ] Decode a downloaded photo fully (not only its first bytes) before it replaces the current one; a truncated JPEG now passes (needs Android `BitmapFactory`; maybe compare with `Content-Length` in `PhotoDownloader` instead)
- [ ] With auto-save off, a photo change is only in memory: the old file is deleted and the link saved before the tree is saved, so closing without saving leaves a media entry with no file; tie the link and file retirement to the deferred save, and check the real media attachment, not only the link, before skipping a sync
- [ ] Photos of a whole family download one after another on load; consider a background pass with a second refresh per photo

### Deferred from photos PR 3

- [ ] "Keep offline" with photos costs one extra API call per person; watch the rate limit on large branches
- [ ] Album repair racing a main-photo change has no per-profile lock; main status is read just before saving, which narrows the window
- [ ] Album thumbnails load through Glide over plain https without auth; check they still work if Geni signs thumbnail URLs per request

### Deferred from photos PR 4

- [ ] Crop right after adding a photo uploads the uncropped file (the tree is saved before the crop and the queue runs at once); needs a short hold on new photo jobs with a wake-up
- [ ] `PhotoScaler`: huge images can decode to about 50 MB (an out-of-memory is now caught as a failed upload); small JPEGs keep their EXIF data (GPS) because they are sent as is; PNG transparency turns black after re-encoding to JPEG; only EXIF orientation 6 is tested
- [ ] Switching the photo upload on re-plans only trees saved in this process; after a cold start the photos wait for the next save of each tree
- [ ] Local photos of Geni people from earlier builds upload on the first save after the update (the switch defaults to on), with no notice
- [ ] A photo whose tag edit failed after upload can get a second local media on the next photo sync (found by tag only)
- [ ] Old photo jobs keep their old label; the pending count may include photo jobs while the switch is off

## Other tasks

- [x] Register a Geni sandbox app and fill `geni.properties` (`geniAppId`, `geniHost=sandbox.geni.com`)
- [x] Own app ID `net.cheack.treekin` and name "Treekin" (in `app/geni.gradle` and `app/src/geni/res`)
- [x] Redirect scheme `net.cheack.treekin://authorize` instead of `app.familygem.geni://` (also change the Callback URL in the Geni app)
- [x] Rename the GitHub repository to `cheack/Treekin`
- [ ] Replace "Family Gem" in other strings (example tree, Premium, about screen)
- [ ] Take the transparent `NumberPicker` divider fix from Sternbach-Software/GeniForAndroid
- [ ] CI: weekly trial merge of `upstream/master`, build and tests

## Progress log

One line per merged PR or finished stage, newest first: what now works for the user, and the PR.

- 2026-11-20: Geni photos, part 3 (PR [#11](https://github.com/cheack/Treekin/pull/11)): the person menu has "Geni photos": the album with thumbnails, tap a photo to download it or "Download all"; "Keep offline" can also fetch all album photos; when the main photo changes on Geni the old one stays on the device as an album photo.
- 2026-11-19: Geni photos, part 2 (PR [#9](https://github.com/cheack/Treekin/pull/9)): the main Geni photo of each loaded person now downloads to the device and shows on the card and in the person's media; an unchanged photo is not downloaded again, a changed one replaces the old, one removed on Geni leaves the device. Also (PR [#10](https://github.com/cheack/Treekin/pull/10)): the `_GENI_ID` tag on the card can no longer be edited or deleted.
- 2026-11-18: Geni photos, part 1 (PR [#8](https://github.com/cheack/Treekin/pull/8)): the app can list, read, upload and delete Geni photos and download photo files safely (no token sent to photo hosts, size cap, cancellable). Nothing is visible to the user yet; the main photo comes next.
- 2026-11-18: UI rules for Geni trees (PR [#7](https://github.com/cheack/Treekin/pull/7)): sharing, merging and comparing are hidden for the Geni tree; unlinking a relative is refused with an explanation; deleting a person offers "Remove from this device" and, for profiles the user manages, "Delete on Geni" after a second confirmation; the user's own profile cannot be removed. Stage 5 done.

- 2026-11-18: Refresh and conflicts, part 3 (PR [#6](https://github.com/cheack/Treekin/pull/6)): Geni trees refresh daily on Wi-Fi and an open card re-checks Geni after an hour; profiles deleted on Geni leave the device unless edited locally (then the user chooses), merged profiles move to the survivor. Stage 4 done.
- 2026-11-17: Refresh and conflicts, part 2 (PR [#5](https://github.com/cheack/Treekin/pull/5)): the Geni account screen lists conflicts; for each field the user keeps their value or takes Geni's, and only kept values are uploaded.
- 2026-11-17: Refresh and conflicts, part 1 (PR [#4](https://github.com/cheack/Treekin/pull/4)): a load from Geni no longer overwrites an edit waiting for upload, and an upload no longer overwrites what someone changed on Geni meanwhile (other fields are merged, the same field stops as a conflict).
- 2026-11-17: Write path, part 3 (PR [#3](https://github.com/cheack/Treekin/pull/3)): edits of a Geni tree are uploaded after each save, offline changes wait for the network, and the Geni account screen shows waiting and failed changes. Stage 3 done.
- 2026-11-17: Write path, part 2 (PR [#2](https://github.com/cheack/Treekin/pull/2)): saved changes become upload jobs (updates of profiles and unions, new children, parents and partners), sent to Geni in order with retries, never adding a person twice. Not wired into the app yet.
- 2026-11-17: Write path, part 1 (PR [#1](https://github.com/cheack/Treekin/pull/1)): edits are compared with the Geni version field by field and turned into Geni update parameters; an upload queue in the sync database. Nothing is uploaded yet.
- 2026-11-17: The app is called Treekin (`net.cheack.treekin`), installs next to Family Gem; the repository is `cheack/Treekin`; CodeRabbit reviews PRs.
- 2026-11-16: Stage 2, read path: a Geni tree is created from the user's own profile; opening a person loads their relatives; relatives within 3 steps are prefetched in background; "Keep offline" downloads a branch. Checked on the sandbox and a Pixel 6.
- 2026-11-16: Stage 1: login to Geni with the mobile OAuth flow, encrypted token, API client with rate limiting.
