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

- [ ] Hook in `TreeUtil.saveJson`: signal that a Geni tree was written
- [ ] Diff of the whole tree against snapshots, with a hash per record
- [ ] Upload queue in Room; job types: update profile, add child/parent/partner (a sibling is a child of the parents' union), update union
- [ ] Mapping GEDCOM → Geni for uploads; local-only data (sources, notes, extra events) never uploaded
- [ ] People created offline: later jobs refer to them by GEDCOM ID, resolved to the Geni ID when they run
- [ ] Queue runner: order, rate limit, retries with back-off (in-process, not WorkManager: upstream cancels all work on save)
- [ ] Permanent failures kept and shown to the user (list of failed changes)

### Deferred from the review of PR 1 (Tasks 1–4)

- [ ] Real round-trip test: upload params → simulated Geni profile → `ofSnapshot` equals the local projection
- [x] `FamilyMerger`: clear `verifiedHash` when the snapshot changes (CodeRabbit, PR 1)
- [ ] `parseDate`: reject days that do not exist in the month (31 FEB)
- [ ] Name text after the closing slash ("Ivan /Petrov/ Jr") is invisible to sync

### Deferred from the review of PR 2 (Tasks 5–7)

- [ ] A new person in two families is added through one; the other relation stays local without notice (Geni has no call to link existing profiles): show it to the user
- [x] `Uploader.refreshSnapshot` read-modify-write can overwrite a loader's link write made at the same time (`fullyLoaded`, `loadedAtMillis`)
- [ ] A load of a profile added without a snapshot applies every Geni field (`FamilyMerger` with `previous = null`) and can overwrite a local edit not uploaded yet (CodeRabbit, PR 2)
- [ ] A person deleted during the add request whose GEDCOM ID is reused at once gets the new link and tag (CodeRabbit, PR 2)
- [ ] When the tag write after an add fails and the person is renamed, the record is ambiguous and its edits are not uploaded: show it to the user (CodeRabbit, PR 2)

Moved out of stage 3: photo upload goes with "Show Geni profile photos" (stage 2); delete profile goes with the delete rules (stage 5).

## 4. Refresh and conflicts

Plan: not written yet.

- [ ] Refresh on open: fetch current Geni version, update cache and snapshot when nothing is pending
- [ ] Daily refresh of radius N on an unmetered network
- [ ] Refresh updates only Geni-mapped fields; local-only data is kept
- [ ] Conflict check before upload using `updated_at`; paused job when someone else changed the profile
- [ ] Conflict screen: both versions, choice per field (based on `share/CompareActivity`)
- [ ] Merged profiles (`merged_into`): remap to the surviving ID
- [ ] Deleted or hidden profiles: removed from the cache unless they have pending changes; otherwise the user decides

## 5. Upstream UI rules

Plan: not written yet.

- [ ] Hide merge, shared-tree import and sharing for Geni trees
- [ ] Delete person in a Geni tree: only managed profiles, with explicit confirmation, then `profile-X/delete`; otherwise blocked
- [ ] "Remove from this device" action (cache only)
- [ ] Block unlinking a spouse or child in Geni trees (no API for it)
- [ ] Backup restore of a Geni tree: reset snapshots, no uploads, ask about pending jobs
- [ ] GEDCOM export stays allowed

## Other tasks

- [x] Register a Geni sandbox app and fill `geni.properties` (`geniAppId`, `geniHost=sandbox.geni.com`)
- [x] Own app ID `net.cheack.treekin` and name "Treekin" (in `app/geni.gradle` and `app/src/geni/res`)
- [ ] Redirect scheme `net.cheack.treekin://authorize` instead of `app.familygem.geni://` (also change the Callback URL in the Geni app)
- [x] Rename the GitHub repository to `cheack/Treekin`
- [ ] Replace "Family Gem" in other strings (example tree, Premium, about screen)
- [ ] Take the transparent `NumberPicker` divider fix from Sternbach-Software/GeniForAndroid
- [ ] CI: weekly trial merge of `upstream/master`, build and tests

## Progress log

One line per merged PR or finished stage, newest first: what now works for the user, and the PR.

- 2026-11-17: Write path, part 1 (PR [#1](https://github.com/cheack/Treekin/pull/1)): edits are compared with the Geni version field by field and turned into Geni update parameters; an upload queue in the sync database. Nothing is uploaded yet.
- 2026-11-17: The app is called Treekin (`net.cheack.treekin`), installs next to Family Gem; the repository is `cheack/Treekin`; CodeRabbit reviews PRs.
- 2026-11-16: Stage 2, read path: a Geni tree is created from the user's own profile; opening a person loads their relatives; relatives within 3 steps are prefetched in background; "Keep offline" downloads a branch. Checked on the sandbox and a Pixel 6.
- 2026-11-16: Stage 1: login to Geni with the mobile OAuth flow, encrypted token, API client with rate limiting.
