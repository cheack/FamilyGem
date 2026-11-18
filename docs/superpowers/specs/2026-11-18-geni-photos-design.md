# Geni photos: design

Date: 2026-11-18. Extends [the sync design](2026-11-16-geni-sync-design.md). Roadmap items: stage 2 "Show Geni profile photos", stage 3 "photo upload".

## Intent

A Geni tree in Treekin shows the photos people have on Geni, and photos added or removed in the app reach Geni.
The user wants all of it: see the main photo, browse a person's album, add photos, set the main photo, delete photos.
Photos are the last missing everyday Geni feature, and the user's own memories live in them.

## Goals

- The main Geni photo of a person shows on the card and in the diagram.
- A person's other Geni photos can be browsed and are kept on the device.
- A photo added in the app is uploaded, offline or not, with the same retry and failure display as other changes.
- A photo can be made the main one.
- A photo can be deleted on Geni from the app (managed profiles only, with confirmation); a photo deleted on Geni leaves the device.
- Photos stay usable offline and survive the end of Geni API access.

## Non-goals

- Documents, videos, photo tags, comments, albums or projects of Geni.
- Editing a photo (crop, rotate) and syncing the edit.
- Uploading photos of profiles the user cannot edit.
- Re-uploading local media that was never meant for Geni (see Local-only rule).

## Anti-goals

- No second copy of photos on every load: a photo already downloaded and unchanged is never fetched again.
- No bulk download of whole trees' albums; the rate limit and storage forbid it.
- Nothing is deleted on Geni without an explicit confirmation by the user.

## Constraints

- Upstream code is touched only through small hooks; photo code lives in `:geni`, in Kotlin.
- Rate limit (sandbox 3 per second, production 40 per 10 s): photo downloads go through the same limiter, or a separate light one for CDN hosts that do not count against the API.
- Tokens must not leak to photo hosts: send `Authorization` only to the Geni API host.
- Geni photo URLs may expire: the app stores files, not URLs, as the lasting copy.
- Sandbox facts so far: `POST profile-X/add-photo` with base64 `file`, `file_name`, `title` sets the profile photo; `add-mugshot` returned `null`.

## Approaches

- **A. Download to files (chosen).** A Geni photo becomes a FamilyGem media file, linked by a Room row to its Geni photo ID. Works offline, fits upstream's media viewer, keeps a local copy. Costs storage, bandwidth and a link table.
- **B. Remote URL only.** Media entry holds the Geni URL. Least code, but breaks offline and when URLs expire, and leaves no local copy.
- **C. Cache with eviction.** A plus deletion when space is low. A lot more code for little gain in a personal app.

The user chose A and the five-PR split below.

## Design

### Data

- Room table `photo_links`: tree ID, Geni photo ID, GEDCOM media ID (`OBJE`), owning Geni profile ID, `isMain`, size or version marker from Geni (`updated_at` or URL), local file name, state (`downloaded`, `pending download`, `pending upload`, `pending delete`).
- Local files go to the app's media folder for the tree, so upstream's viewer, export and backup handle them; the exact folder and naming are to be settled in PR 2 after reading `MediaFolders` code.
- The person's `OBJE` record carries the file; `_GENI_PHOTO_ID` custom tag marks Geni-linked media, like `_GENI_ID` on people.

### Local-only rule

Only media with a `photo_links` row or one the user explicitly sends to Geni is uploaded. A media entry on a Geni person that has no link is never uploaded by the diff. In PR 4 the user chooses "Send to Geni" per photo when adding it; the default for a new photo added to a Geni person is to upload it (the user is working in a Geni tree), with a switch to keep it local.

### Read (PR 2, PR 3)

- `GeniProfile.mugshotUrls` gives the main photo; pick one size (about 600 px, to be confirmed in PR 1) and download it on person load, in the loader's existing coroutine, after the merge is saved.
- Album: `profile-X/photos` (name to be confirmed in PR 1) lists photos with IDs and URLs; the list is fetched when the user opens the album, not on every load; downloads on demand.
- Three-way merge applies to photos by Geni photo ID: new on Geni, added locally; gone from Geni's list, removed locally unless it has a pending local change.
- "Keep offline" downloads the main photo of each person in the branch; album photos only if the user asks for them (setting off by default).

### Write (PR 4)

- New job types: `ADD_PHOTO` (file path, title, make-main flag), `SET_MAIN_PHOTO`.
- The planner finds new linked-eligible `OBJE` entries on Geni persons in the same diff pass that finds other changes.
- Upload via `add-photo` (base64 or multipart, as the sandbox shows); the result's photo ID is stored in `photo_links`.
- Large photos are scaled down before upload to a size cap decided in PR 4 (Geni accepts files of limited size; confirm in PR 1).

### Delete and conflicts (PR 5)

- Delete job `DELETE_PHOTO` for managed profiles only; reuses stage 5's managed-profile check and a confirmation dialog, which says the photo is deleted on Geni too.
- A photo deleted on Geni and unchanged locally is removed from the device and from the `OBJE` list. A photo with local edits (title, notes) goes to the existing conflict list.
- Main photo changed on both sides: the Geni side wins for the main flag, the local file stays as an album photo.

### Split into PRs

1. **API client:** sandbox check; `GeniPhoto` model; client methods (list, get, upload, delete, set main); photo download helper with host rule; unit tests with fixtures. No user-visible change.
2. **Main photo, read:** `photo_links` table and migration; mapper adds the main photo; download; card and diagram show it; refresh keeps it current.
3. **Album, read:** album list and viewer; on-demand download; "Keep offline" option.
4. **Upload:** `ADD_PHOTO` and `SET_MAIN_PHOTO` jobs; planner; UI to add or choose main; failure display.
5. **Delete and conflicts:** `DELETE_PHOTO`; removals from Geni; conflict cases; roadmap and progress log final.

Each PR follows the project workflow: feature branch, plan checklist, CodeRabbit review loop, merge into `geni`, progress-log line.

## Left to the builder

- Exact Geni endpoint names, photo sizes and upload size limit (verify in PR 1 on the sandbox and record in the geni-api memory).
- Local folder and file naming, and how the `OBJE` is created through upstream's media API.
- Whether downloads share the API rate limiter.
- Wording of dialogs and strings (English only, like other Geni strings).

## Open risks

- Geni may return photo URLs that need a token or expire quickly; PR 1 settles this and may change the download step.
- Upstream's media viewer may not like files that change on disk; PR 2 checks cache invalidation.
