# Person cards redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Horizontal diagram person cards (photo left, text right, sex stripe + tint) shipped only as overlay resources.

**Architecture:** Overlay `diagram_card.xml` and `person_border_{male,female,undefined}.xml` in `app/src/geni/res` (applied to debug and release source sets by `app/geni.gradle`). View IDs stay identical, so `DiagramFragment.GraphicPerson` is untouched.

**Tech Stack:** Android XML resources, ConstraintLayout.

**Spec:** `docs/superpowers/specs/2026-11-20-geni-person-cards-design.md`

## Global Constraints

- No upstream file edited; no change to `DiagramFragment.kt`. If the layout needs a code change, stop and ask the user.
- Same view IDs: `card_background`, `card_border`, `card_picture`, `card_name`, `card_title`, `card_data`, `card_mourn`.
- No Geni logo, name, colours or gradients on the cards.
- Photo 64dp; sex stripe 4dp; light and night themes readable.
- Build and test only in an rsync copy (see memory `build-env`); phone Pixel 6 over adb, `--user 0`.

## Review Focus

- Card without photo (`card_picture` GONE): text must not leave an empty gap or shrink below the stripe.
- Very long name or title: wraps inside `maxWidth`, card height grows, nothing clipped.
- Night theme: tint and text stay readable.
- Right-to-left locale: stripe and photo flip with the card (`layoutDirection` is locale); use `start`/`end`, not `left`/`right`, in the layout and `gravity="start"` in the stripe.
- Selected person (`person_background_selected`) and partner background still show behind the tint.
- Card without sex (undefined) gets a grey stripe, not a missing one.

---

### Task 1: Overlay card layout and drawables

**Files:**
- Create: `app/src/geni/res/layout/diagram_card.xml`
- Create: `app/src/geni/res/drawable/person_border_male.xml`, `person_border_female.xml`, `person_border_undefined.xml`

**Interfaces:**
- Consumes: IDs and drawables listed in Global Constraints; colours `@color/male`, `@color/female`, `@color/undefined` from upstream `values/colors.xml`.
- Produces: nothing for later tasks.

- [ ] **Step 1: Border drawables.** Each is a `layer-list` of two items on `card_border` (full-card view): (a) rounded rectangle, radius 4dp, solid tint = the sex colour at alpha 0x26 (hex literal, e.g. `#2644AAFF` male, `#26FF66DD` female, undefined reuses the `@color/undefined` RGB at 0x26); (b) item `width=4dp`, `gravity=start`, shape with solid `@color/male|female|undefined` and `topLeftRadius`/`bottomLeftRadius` 4dp. Drop the old 2dp stroke.
- [ ] **Step 2: Card layout.** Copy upstream `diagram_card.xml` as the starting point, keep root ConstraintLayout, `card_background`, `card_border` and `card_mourn` as they are. Replace the content `LinearLayout` with a horizontal one (`paddingStart=10dp`, other paddings 6dp so the 4dp stripe never overlaps content, `selectableItemBackground`): `card_picture` 64dp x 64dp `centerCrop`, then a vertical text column (`layout_marginStart=8dp`, gravity start, center vertical) with `card_name` 15sp bold-less, `card_title` 13sp italic, `card_data` 13sp `@color/text_veiled`, all `maxWidth=180dp`, `ElasticTextView` kept.
- [ ] **Step 3: Build.** Run: `JAVA_HOME=/home/vp/jdk/jdk-21 <gradle-9.9.0>/bin/gradle -q --console=plain :geni:testDebugUnitTest :app:assembleDebug` in the rsync copy. Expected: no output (pass).
- [ ] **Step 4: Phone check.** Install the APK; open the diagram of the sandbox tree and an upstream-style local tree; use adb screencap and Read the images. Verify every Review Focus line (long name: temporarily rename a person to 60 characters, then restore; night theme: `adb shell cmd uimode night yes`; RTL: `adb shell setprop` is not allowed, so use developer option "Force RTL" or skip with a note). Report anything not checked.
- [ ] **Step 5: Commit** on branch `geni-cards-1`: `git add app/src/geni/res docs`, detailed header + body, trailer `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.

### Task 2: Geni branding audit

**Files:**
- Read: login screen layout and strings in `geni/`, About screen (`app/src/main/res/layout/about_activity.xml`, `app/src/geni/res/values/strings.xml`), launcher icon.
- Modify: roadmap `docs/superpowers/plans/README.md` (tick Task, add a dated progress-log line after merge).

**Interfaces:** none.

- [ ] **Step 1: Audit** against the guidelines at https://www.geni.com/platform/developer/help/branding and `/terms` (read the live pages, not a summary): app name without "Geni"; disclaimer "not endorsed, operated, or sponsored by Geni.com" on the About screen; Geni logo beside the login button, button text "Log in with Geni" or "Connect with Geni"; no Geni logo altered.
- [ ] **Step 2: Report** gaps to the user in a short list. Fix text-only gaps (strings) in this PR through `scripts/gen_geni_strings.py` conventions; list logo or icon gaps as a follow-up under "Deferred" in the roadmap instead of fixing them here.
- [ ] **Step 3: Commit** the roadmap and any string fixes.
