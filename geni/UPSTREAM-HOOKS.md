# Upstream hooks

Every change to upstream FamilyGem files made by this fork.
Each code hook is marked with a `GENI-HOOK` comment; after a merge from upstream run `git grep GENI-HOOK` and compare with this list.

| File | Change | Why |
|---|---|---|
| `settings.gradle` | `include ':geni'` | Adds the Geni module. |
| `app/build.gradle` | `apply from: 'geni.gradle'` | Fork build changes live in `app/geni.gradle`. |
| `.gitignore` | `geni.properties` | Keeps Geni app credentials out of git. |
| `app/src/main/java/app/familygem/TreesActivity.kt` | `GeniGlue.addTreesMenu(this, bar.menu)` after `bar.inflateMenu(R.menu.trees_menu)` | "Geni account" item in the trees list menu. |
| `build.gradle` | KSP Gradle plugin on the buildscript classpath | Room code generation in `:geni`; the plugin must share the classloader with AGP. |
| `app/src/main/java/app/familygem/profile/ProfileActivity.kt` | `GeniGlue.onPersonOpened(this, person) { refresh() }` at the end of `onCreate` | Loads the person's relatives from Geni when the card opens. |
| `app/src/main/java/app/familygem/profile/ProfileActivity.kt` | `GeniGlue.addProfileMenu(this, menu, person)` in `onCreateOptionsMenu` | "Keep offline" item for people of Geni trees. |
| `app/src/main/java/app/familygem/util/TreeUtil.kt` | `GeniGlue.onTreeDeleted(treeId)` at the start of `deleteTree` | Drops the Geni rows of a deleted tree, so a new tree with the same ID does not inherit them. |
