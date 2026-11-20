# Other tasks (Treekin strings, NumberPicker check, weekly upstream CI) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the three open "Other tasks" of the roadmap: no "Family Gem" in user-visible strings of the Treekin build, confirm the NumberPicker divider needs no fix, and a weekly CI job that trial-merges `upstream/master`, builds and tests.

**Architecture:** Strings are overridden in the existing build-type resource overlay `app/src/geni/res` (generated from the upstream translations by a small script, so `app/src/main/res` stays untouched and upstream merges stay cheap). The NumberPicker task is a verification only. CI is one GitHub Actions workflow with a cron trigger.

**Tech Stack:** Android resources, Python 3 (stdlib only) for the generator, GitHub Actions, Gradle 9.9.0, JDK 21.

**Spec:** `docs/superpowers/specs/2026-11-16-geni-sync-design.md` (no section covers these tasks; the roadmap "Other tasks" in `docs/superpowers/plans/README.md` is the source) and the "Upstream relationship" rules in `CLAUDE.md`.

## Global Constraints

- Do not edit anything under `app/src/main/res` or upstream Java/Kotlin (keep `git merge upstream/master` cheap).
- App name stays "Treekin"; never use "Geni" in user-visible app naming.
- Persisted text (code comments, docs, commits) in normal English.
- Never commit secrets; the workflow must build without `credential.properties` and `geni.properties`.
- Build/test only in an rsync copy (see handoff notes): `JAVA_HOME=/home/vp/jdk/jdk-21 <gradle 9.9.0> -q --console=plain :geni:testDebugUnitTest :app:assembleDebug`, no output means pass.
- Build: AGP 9.4, Kotlin 2.3, `compileSdk` 37.2, `minSdk` 23.

## Review Focus

- A locale string with an apostrophe or `%1$s` placeholder must be copied verbatim (the generator copies raw XML text, only "Family Gem" changes).
- A locale where "Family Gem" is inflected or transliterated (ru, uk, bg, fa, iw, ckb, zh...) must not be left half-replaced: the generator reports strings whose text still matches `family\s*gem` case-insensitively after replacement.
- Upstream adds or changes a "Family Gem" string later: `--check` mode fails, so CI notices the stale overlay.
- Trial merge with conflicts must fail the job with the conflicted file list, and must never push anything.
- Workflow must work without `local.properties` (SDK path from `ANDROID_HOME`).

---

### Task 1: Treekin strings in the geni overlay

**Files:**
- Create: `scripts/gen_geni_strings.py`
- Create (generated, committed): `app/src/geni/res/values-<locale>/strings.xml` for each locale that mentions "Family Gem"
- Modify: `app/src/geni/res/values/strings.xml`
- Modify: `docs/superpowers/plans/README.md` (tick the item)

**Interfaces:**
- Produces: `python3 scripts/gen_geni_strings.py [--check]`. Without flag: rewrites overlay files. With `--check`: writes nothing, exits 1 and prints the stale files when the overlay differs from what would be generated. Task 3 calls `--check`.
- Strings handled: every `<string>` in `app/src/main/res/values*/strings.xml` whose text contains "Family Gem" and whose name is not `app_name` (currently `family_gem_example`, `family_gem_premium`, `unlock_function`). Names stay the same.

- [ ] **Step 1: Write the generator `scripts/gen_geni_strings.py`**

  Parse each `app/src/main/res/values*/strings.xml` with `xml.etree.ElementTree`; for strings containing "Family Gem" (case-sensitive) write `<string name=...>` with "Family Gem" replaced by "Treekin" into `app/src/geni/res/<same values dir>/strings.xml`, keeping `translatable="false"` when present. Default `values/strings.xml` in the overlay keeps its hand-written `app_name` override; the generator must not overwrite that file, it only appends/refreshes a block between `<!-- GENERATED:BEGIN -->` and `<!-- GENERATED:END -->` markers (add the markers in this task). Locale files are fully generated, with a header comment saying so. After replacement, print a warning line for every string still matching `family\s*gem` (case-insensitive).

- [ ] **Step 2: Add markers to `app/src/geni/res/values/strings.xml`**, keep the existing `app_name` override above them.

- [ ] **Step 3: Run the generator, then `--check`**

  Run: `python3 scripts/gen_geni_strings.py && python3 scripts/gen_geni_strings.py --check; echo $?`
  Expected: generator lists written files and any warnings; `--check` prints nothing and the echo shows `0`.

- [ ] **Step 4: Check the output by hand**

  Read `app/src/geni/res/values/strings.xml`, `values-ru`, `values-zh`, `values-fa`: apostrophes and `%` placeholders intact, no "Family Gem" left. Fix by hand-editing the script's replacement table only if the warning list is non-empty (e.g. a transliterated name); do not hand-edit generated files.

- [ ] **Step 5: Build in the rsync copy**

  Run the Global Constraints build command. Expected: no output (pass), no duplicate-resource error.

- [ ] **Step 6: Tick "Replace Family Gem in other strings" in `docs/superpowers/plans/README.md`, then commit** (header e.g. "Replace Family Gem with Treekin in overlay strings", body explains the generator and `--check`).

---

### Task 2: NumberPicker divider (verify, no code)

**Files:**
- Modify: `docs/superpowers/plans/README.md`

Finding from the survey: upstream already hides the divider through the theme (`AppTheme.Wheel` applies `AppTheme.Wheel.TransparentLines`, `colorControlNormal` transparent, `app/src/main/res/values/styles.xml:114-124`) and no code touches `selectionDivider` or uses reflection. The Sternbach change is therefore not needed.

- [ ] **Step 1: Confirm no reflection on NumberPicker**

  Run: `grep -rn "selectionDivider\|mSelectionDivider\|getDeclaredField" app/src/main/java`
  Expected: no hit about NumberPicker.

- [ ] **Step 2: Tick the item in the roadmap and change its text to "NumberPicker divider fix: not needed, upstream hides the divider through the theme (`AppTheme.Wheel.TransparentLines`)"; commit.**

---

### Task 3: Weekly upstream trial merge workflow

**Files:**
- Create: `.github/workflows/upstream-check.yml`
- Modify: `docs/superpowers/plans/README.md` (tick, add progress-log line)

**Interfaces:**
- Consumes: `scripts/gen_geni_strings.py --check` from Task 1.
- Produces: workflow "Upstream trial merge" with triggers `schedule` (`0 6 * * 1`, Monday 06:00 UTC) and `workflow_dispatch`.

- [ ] **Step 1: Write the workflow**

  Single job on `ubuntu-latest`, `permissions: contents: read`. Steps in order: `actions/checkout@v4` with `ref: geni`, `fetch-depth: 0`; add remote `upstream` = `https://github.com/michelesalvador/FamilyGem.git` and fetch it; set a throwaway git identity; `git merge --no-commit --no-ff upstream/master` (on failure print `git diff --name-only --diff-filter=U` and exit 1; never push); `actions/setup-java@v4` Temurin 21; `gradle/actions/setup-gradle@v4` with `gradle-version: 9.9.0`; install `platforms;android-37.2`-compatible platform and build-tools via `sdkmanager` after `yes | sdkmanager --licenses` (use the exact package names the local build resolved: read `~/Android/Sdk/platforms` and `build-tools` for the names); run `python3 scripts/gen_geni_strings.py --check`; run `gradle --console=plain :geni:testDebugUnitTest :app:assembleDebug`. A job failure is the alert (GitHub emails the repo owner).

- [ ] **Step 2: Validate locally**

  Run: `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/upstream-check.yml'))"` (if PyYAML is missing, use `ruby -ryaml -e 'YAML.load_file(ARGV[0])' .github/workflows/upstream-check.yml`).
  Expected: no error.

- [ ] **Step 3: Dry-run the merge step locally in a throwaway clone**

  Clone the repo into the scratchpad, `git fetch upstream`, run the same merge command, report conflicts (list may be non-empty today; that is useful information for the user, not a failure of this task).

- [ ] **Step 4: Tick the roadmap item, add the progress-log line (newest first, with the PR link once known), commit.**

- [ ] **Step 5: Push the branch, open the PR (title without the word "upstream", CodeRabbit skips such titles), run `workflow_dispatch` once with `gh workflow run` and report the result.**

  Note: a workflow file on a non-default branch can only be dispatched after merge into `geni` (the default branch); if dispatch is impossible before merge, say so and run it right after the merge.
