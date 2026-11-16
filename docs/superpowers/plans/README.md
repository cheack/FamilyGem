# Geni sync roadmap

Design: [2026-11-16-geni-sync-design.md](../specs/2026-11-16-geni-sync-design.md).
Each stage gets its own detailed plan with a step checklist; steps are ticked (`- [x]`) as they are done and committed.
The items under each stage are the deliverables; the stage's plan breaks them into tested steps.

## 1. API client and login

Plan: [2026-11-16-geni-1-api-login.md](2026-11-16-geni-1-api-login.md)

- [ ] Config from `geni.properties` (`geniAppId`, `geniHost`, `geniRedirectUri`); no client secret in the app
- [ ] Rate limiter driven by `X-API-Rate-Limit` / `X-API-Rate-Remaining` / `X-API-Rate-Window`
- [ ] OAuth mobile flow: authorize URL and redirect parsing (token, denial)
- [ ] Encrypted token store; API client with bearer token, 401 and 429 handling
- [ ] Login screen and "Geni account" item in the trees menu (hook in `TreesActivity`)
- [ ] Manual check on the sandbox: login, cancel, redirect scheme, real token lifetime

## 2. Read path

Plan: not written yet.

- [ ] Sandbox exploration: real responses of `profile`, `immediate-family`, `union`; dates and locations format
- [ ] Room database in `:geni`: Geni tree marker, ID mapping (GEDCOM ID ↔ Geni ID), snapshots
- [ ] Mapping Geni → GEDCOM: profile → `INDI`, union → `FAM`, `_GENI_ID`, standard fields, `_GENI_*` for the rest
- [ ] Create a Geni tree from the logged-in user's profile (entry in the trees list, root = own profile)
- [ ] Lazy load: opening a person fetches `immediate-family` and merges it into the cache (hook in person card or diagram)
- [ ] Edge people marked as not fully loaded; opening them triggers a fetch
- [ ] Background prefetch within N steps of the root (N = 3, setting), WorkManager
- [ ] "Keep offline" action: download a person's branch to a chosen depth
- [ ] Measure size and time of a radius-3 prefetch and of saving a large cache JSON

## 3. Write path

Plan: not written yet.

- [ ] Hook in `TreeUtil.saveJson`: signal that a Geni tree was written
- [ ] Diff of the whole tree against snapshots, with a hash per record
- [ ] Upload queue in Room; job types: update profile, add child/parent/partner/sibling, update union, upload photo, delete profile
- [ ] Mapping GEDCOM → Geni for uploads; local-only data (sources, notes, extra events) never uploaded
- [ ] Temporary IDs for people created offline; rewrite of later jobs when Geni returns the real ID
- [ ] Queue worker: order, network constraint, rate limit, retries
- [ ] Permanent failures kept and shown to the user (list of failed changes)

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
- [ ] Delete person in a Geni tree: only managed profiles, with explicit confirmation; otherwise blocked
- [ ] "Remove from this device" action (cache only)
- [ ] Block unlinking a spouse or child in Geni trees (no API for it)
- [ ] Backup restore of a Geni tree: reset snapshots, no uploads, ask about pending jobs
- [ ] GEDCOM export stays allowed

## Other tasks

- [ ] Register a Geni sandbox app and fill `geni.properties` (`geniAppId`, `geniHost=sandbox.geni.com`)
- [ ] Take the transparent `NumberPicker` divider fix from Sternbach-Software/GeniForAndroid
- [ ] CI: weekly trial merge of `upstream/master`, build and tests
