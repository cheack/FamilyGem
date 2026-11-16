# Geni sync design

Date: 2026-11-16. Status: approved. Module: `:geni` (`app.familygem.geni`).

## Intent

The Geni.com website is not adapted to phones and is slow.
The user wants a fast, phone-friendly client for Geni built on FamilyGem's UI (diagram, person card, editors, search).
The user does everything on Geni from the phone: browsing, searching, adding people, editing facts, photos, and sometimes without internet.

## Scope of the first version

Personal use first: one user, one Geni account, sandbox and the user's own production access.
No decision may block a later Play Store release; multi-account support, Geni production app approval, privacy policy and terms come later.

## Goals

- Browse and search the user's part of the Geni world tree with FamilyGem UI, with no network lag on screens.
- Add and edit people, unions, facts and photos in the app; changes reach Geni.
- Work offline on cached people; queued changes upload when the network is back.
- Pick up changes other Geni users made to cached profiles.
- Keep `git merge upstream/master` cheap (see `geni/UPSTREAM-HOOKS.md`).

## Non-goals

- Downloading the whole Geni world tree or the user's whole tree in one go.
- Using Geni as a backup or export target for existing local FamilyGem trees (no "upload my GEDCOM to Geni").
- Geni projects, documents, discussions, revisions history UI (later, if ever).
- Merging duplicate profiles on Geni from the app (later).

## Anti-goals (failure even if it works)

- Silently overwriting changes other Geni users made.
- Spreading Geni logic through many upstream classes, so that upstream merges conflict often.
- Hitting Geni rate limits so hard that the app gets throttled or the key revoked.
- Breaking ordinary local FamilyGem trees.

## Constraints

- Geni is the source of truth: the tree is shared and edited by other people; Geni merges profiles.
- Rate limits (as of 2026-11): 40 requests per 10 s with a token, 5 without. Read `X-API-Rate-Limit`, `X-API-Rate-Remaining`, `X-API-Rate-Window`; back off.
- OAuth 2.0, no Android SDK; own client on OkHttp + Gson (already dependencies).
- Secrets only in `geni.properties` (git-ignored), exposed as `BuildConfig.GENI_APP_ID` / `GENI_APP_KEY` of `:geni`.
- Upstream code touched only through one-line hooks marked `// GENI-HOOK` and listed in `geni/UPSTREAM-HOOKS.md`.
- New code in Kotlin; GPL-3.0; no proprietary dependencies.
- Branding: the app name must not be "Geni".

## Approaches considered

- **A. Geni cache as an ordinary FamilyGem tree plus a separate sync database (chosen).**
  The cache is a normal GEDCOM-JSON tree, so all FamilyGem screens work unchanged.
  `:geni` keeps its own Room database: ID mapping, upload queue, last-synced snapshots.
  Local edits are detected through one hook on tree save by comparing changed records with their snapshots.
  Trade-off: Geni fields without a GEDCOM equivalent go to custom tags, some lose precision; the whole tree JSON is rewritten on each save, which may get slow with thousands of profiles.
- **B. Own Geni model in Room as the master, GEDCOM generated from it for display.**
  Lossless Geni model and fast partial updates, but two models with translation both ways, and edits from FamilyGem editors still need diffing. About twice the code. Kept as the fallback if A gets too slow; UI does not change if we switch.
- **C. Capture edits inside each FamilyGem editor.**
  Exact knowledge of user intent, but needs dozens of hooks in upstream code: conflicts on every upstream merge. Rejected.

The user picked A.

## Design

### Geni tree

- A Geni tree is an ordinary entry in `Settings.trees`, stored as `<treeId>.json` like any other tree, marked as a Geni tree.
  The marker and all Geni metadata (account, root profile ID, settings) live in the `:geni` database keyed by tree ID, not in `Settings.Tree`, so upstream classes stay unchanged.
- One Geni tree per Geni account. Ordinary local trees keep working as before.
- The root person of the tree is the logged-in user's own Geni profile.

### Mapping GEDCOM to Geni

- Geni profile ↔ GEDCOM `INDI`; Geni union ↔ GEDCOM `FAM`.
- Each mapped record carries its Geni ID as a custom tag (`_GENI_ID`), so the link survives GEDCOM export.
  The Room database holds the same mapping for fast lookup; the tag is the backup if the database is lost.
- Geni fields with a GEDCOM equivalent (names, sex, birth/death/burial events with dates and places, living flag, photos) map to standard tags.
- Geni fields without one (for example "about me", nicknames, display name) go to custom `_GENI_*` tags so they round-trip unchanged. The exact list is decided against the real API responses.

### Loading (lazy cache)

- Opening a person while online fetches `profile/immediate-family` for that person and merges the result into the cache; screens show the cached data at once and refresh when the fetch completes.
- Background prefetch keeps everyone within N relationship steps of the root person cached. N defaults to 3 and is a setting.
- "Keep offline" action on any person downloads that person's branch to a chosen depth.
- Everything ever opened stays in the cache.
- People at the edge of the cache (known only as a relative of a cached person) are stored with what Geni returned and marked as not fully loaded; opening them triggers a fetch.

### Local edits and the upload queue

- Edits go to the local tree first; screens update immediately, no waiting for the network.
- Hook (builder's call): one `// GENI-HOOK` line at the end of `TreeUtil.saveJson` tells `:geni` that a Geni tree was written.
  `TreeUtil.save` is not used for detection: it does not receive deleted persons, some callers pass no records, and with `autoSave` off nothing is written until the user taps Save.
  Upload jobs come only from what was actually saved.
- On that signal the sync worker diffs the whole tree against the snapshots, using a hash per record to skip unchanged ones, and turns the differences into queue jobs, for example: update profile fields, add child/parent/partner/sibling, update union, upload photo, delete profile.
- Jobs are stored in Room and run in order by WorkManager (already a dependency) when the network is available, within the rate limit.
- People created offline get a temporary local ID; when Geni returns the real profile ID, the mapping is updated and later jobs that reference the temporary ID are rewritten.
- Data Geni cannot store (sources, citations, notes, repositories, events outside Geni's set, extra names, and the like) is kept locally only and never uploaded (builder's call).
  Refresh from Geni updates only the fields mapped to Geni, so local-only data is never wiped, and a profile with only local-only edits still refreshes.
- A job that fails for a permanent reason (validation, no permission on that profile) is kept and shown to the user, not retried forever and not dropped silently.

### Delete and unlink

- Deleting a person in a Geni tree deletes the profile on Geni only if the user manages that profile, and only after an explicit confirmation that names the consequence for the shared tree. Otherwise deletion is blocked with an explanation.
- A separate "Remove from this device" action drops a person from the cache only; nothing is sent to Geni. The person is not prefetched again unless opened.
- Removing a relationship (unlinking a spouse or child from a family) is blocked in Geni trees until the sandbox shows an API way to do it.

### FamilyGem features on a Geni tree

- Hidden for Geni trees (one-line hooks): merging another tree into it (`merge/`), importing shared-tree updates (`share/CompareActivity`, `ProcessActivity`), sharing through the FamilyGem server (`SharingActivity`, which would send other Geni users' data, living people included, to a third-party server).
- Allowed: GEDCOM export (a local file made by the user).
- Allowed: backup restore, which restores the cache only. After a restore, snapshots are reset to the restored content, so nothing is uploaded, and pending jobs for that tree are shown to the user to keep or drop. Profiles then refresh from Geni as usual.

### Changes made by others and conflicts

- Refresh cadence (builder's call): profiles within radius N are re-checked once a day on an unmetered network; other cached profiles are re-checked when opened online.
- When a cached profile is opened online, or during background refresh, the app fetches its current Geni version and updates the cache and snapshot if nothing is pending locally for it.
- Before uploading an update, the worker checks the profile's current version on Geni against the snapshot. If someone else changed it meanwhile, the job is paused and the user sees both versions and chooses, field by field where possible. FamilyGem's compare screen (`share/CompareActivity`) is the starting point for this UI.
- Profiles merged on Geni (old ID now points to another profile) update the mapping to the surviving ID.
- Profiles deleted on Geni or no longer visible to the user are removed from the cache only if they have no pending local changes; otherwise the user decides.

### Account

- OAuth 2.0 login with the Geni mobile flow; tokens stored encrypted (`androidx.security:security-crypto` is already a dependency).
- Sandbox (`sandbox.geni.com`) and production are selectable in debug builds.

### Upstream hooks (expected)

- `TreeUtil.saveJson`: signal that a tree was written.
- Hide merge, shared-tree import and sharing for Geni trees (about 2–3 lines in menus or activities).
- Delete person: route Geni trees to the Geni delete rules above.
- Backup restore: tell `:geni` that a tree was restored.
- An entry point in the UI to log in and create the Geni tree (for example a menu item in the trees list).
- A trigger to fetch a person when opened (person card or diagram).
- Each new hook is one line, marked `// GENI-HOOK`, and added to `geni/UPSTREAM-HOOKS.md`.

## Left to the builder

- Exact Room schema, job types and their payloads.
- How the worker reads and writes the tree safely while the user edits it (the opened tree lives in `Global.gc`; a closed tree is read with `TreeUtil.openGedcomTemporarily`). It must not lose user edits or corrupt the JSON.
- Rate-limit handling details (token bucket, back-off on headers).
- The exact field list for `_GENI_*` tags, decided from real sandbox responses.
- Where exactly each UI hook goes, as long as each is one line.

## To verify on the sandbox before relying on it

Found on the sandbox (2026-11-16):
- The redirect URI must equal the Callback URL in the Geni app settings; with an empty Callback URL Geni shows "an unexpected error has occurred".
- Geni cannot redirect to `<appId>://authorize` from its mobile docs (a numeric scheme); `app.familygem.geni://authorize` works and is the default.
- The desktop flow on the sandbox fails with "redirect must be provided".
- `client_id` = app ID works; the app secret is not needed.

- Prefetch, radius 3 on the sandbox test tree: 7 loads (21 requests) in 13.3 s at the sandbox limit of 3 requests per second; the tree had 12 people and 5 families in 10.8 KB of JSON (about 0.9 KB per person, so about 1 MB per 1000 people). Approach A still looks fine; saving time on large trees is to watch.
- WorkManager cannot run Geni background work: upstream `Notifier` calls `WorkManager.cancelAllWork()` on every tree save. Prefetch runs in an in-process coroutine scope instead, so it does not survive process death; it restarts from the next tree creation or "Keep offline".

Still open:

- Which OAuth flow Geni allows for a mobile app and how token refresh works.
- What `immediate-family` returns, and how many requests it takes per person.
- Whether Geni exposes a profile version or `updated_at` usable for conflict detection.
- How merged and deleted profiles look in API responses.
- Whether the API can remove a relationship without deleting a profile, and which profiles the user may delete.
- Real size and speed of a radius-3 prefetch.
- Whether Geni echoes the OAuth `state` parameter on the mobile flow. If it does, make `state` mandatory in `OAuth.parseRedirect` (now a missing `state` is accepted while a login is pending).
- Which characters a real token contains (an unencoded `+` would be decoded as a space).
- Which HTTP code Geni returns for a revoked or expired token (the client treats only 401 as "log in again").
