# CLAUDE.md

## Project

Fork of [michelesalvador/FamilyGem](https://github.com/michelesalvador/FamilyGem) (remote `upstream`) at `cheack/Treekin` (remote `origin`; app name "Treekin", application ID `net.cheack.treekin`).
Goal: a full genealogy Android app with two-way sync to [Geni.com](https://www.geni.com) through the Geni API, built on top of FamilyGem.
FamilyGem already provides the GEDCOM data model, tree diagram, editors, import/export and translations; the new work is the Geni layer.

## Upstream relationship

- Keep Geni code isolated in package `app.familygem.geni` (or a separate Gradle module later).
- Touch upstream classes minimally and only through small hooks, so `git merge upstream/master` stays cheap.
- Do not mass-reformat, rename or translate upstream code.
- Sternbach-Software/GeniForAndroid was evaluated and rejected as a base: it has no Geni code and a stale Java-to-Kotlin port.
  Its only useful change is the transparent `NumberPicker` divider fix (illegal reflection access on API > 33).

## Build

- Android Gradle Plugin 9.4, Kotlin 2.3, `compileSdk` 37.2, `minSdk` 23, Java 8 bytecode with core library desugaring.
- No Gradle wrapper is committed: build with Android Studio or a system `gradle`.
- Unit tests: `app/src/test` (JUnit 4). Instrumented tests: `app/src/androidTest`.
- Upstream code is mixed Java and Kotlin, with some Italian names left. New code: Kotlin.
- HTTP: OkHttp is already a dependency; Gson for JSON. GEDCOM model: `org.familysearch.gedcom` (gedcom5-java).

## Secrets

- Never commit Geni app key/secret or any token.
- Keep them in `credential.properties` (git-ignored, already loaded by `app/build.gradle`) and expose them through `buildConfigField`, like the existing `PASSKEY`.
- The build must still work without the file (empty values).

## Geni API

- Docs: https://www.geni.com/platform/developer/help — app registration: https://www.geni.com/platform/developer/apps (sandbox: sandbox.geni.com, separate registration).
- Auth: OAuth 2.0. No Android SDK; write our own client.
- Key endpoints: `profile` (`add-child`, `add-parent`, `add-partner`, `add-sibling`, `update`, `delete`, `merge`, `immediate-family`, `ancestors`, `search`), `union` (`add-child`, `add-partner`, `update`), `user` (`managed-profiles`, `max-family`).
- Rate limits (as of 2026-11): 40 requests per 10 s with a token, 5 without. Read `X-API-Rate-Limit`, `X-API-Rate-Remaining`, `X-API-Rate-Window` headers and back off.
  Load trees lazily (`immediate-family`) and cache locally instead of bulk-downloading.
- Geni API terms: license is revocable and may become paid; the app needs its own privacy policy and terms; follow Geni branding guidelines (do not use "Geni" as the app name without permission).

## License

GPL-3.0 (inherited from FamilyGem). Distributed builds must ship full source under GPL-3.0, keep the original copyright and mark changes.
Do not add closed-source or proprietary components.

## Progress tracking

- Roadmap and plans: `docs/superpowers/plans/README.md` and one plan per stage. Tick `- [x]` items as work is committed.
- When a PR is merged or a stage is finished, add one dated line at the top of the roadmap's "Progress log": what now works for the user, and the PR link.
