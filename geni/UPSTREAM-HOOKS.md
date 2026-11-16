# Upstream hooks

Every change to upstream FamilyGem files made by this fork.
Each code hook is marked with a `GENI-HOOK` comment; after a merge from upstream run `git grep GENI-HOOK` and compare with this list.

| File | Change | Why |
|---|---|---|
| `settings.gradle` | `include ':geni'` | Adds the Geni module. |
| `app/build.gradle` | `apply from: 'geni.gradle'` | Fork build changes live in `app/geni.gradle`. |
| `.gitignore` | `geni.properties` | Keeps Geni app credentials out of git. |
