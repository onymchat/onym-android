# :strings extraction notes

## Contents
Resources only — no Kotlin/Java sources, no tests, no test fixtures.

Moved from `app/`:
- `app/src/main/res/values/strings.xml` -> `modules/strings/src/main/res/values/strings.xml`
- `app/src/main/res/values-ru/strings.xml` -> `modules/strings/src/main/res/values-ru/strings.xml`

## Public API / visibility audit
No top-level Kotlin declarations exist in this module, so there is
nothing to mark `internal`. The module's entire public surface is the
generated `app.onym.android.strings.R` class (R.string.* entries),
consumed by:
- `app/src/main/AndroidManifest.xml` (`@string/app_name`)
- every `R.string.*` reference across `app/` and sibling modules
  (integrator rewrites imports repo-wide).

Public symbols: 0 (hand-written). Internal symbols: 0.

## Dependency trims
All of `app/build.gradle.kts`'s dependencies were trimmed — this module
has no code, so it needs none of them (no compose/serialization/KSP
plugins either). `dependencies {}` is intentionally empty.

## Lint gate
The `MissingTranslation` hard gate (checkReleaseBuilds = true,
abortOnError = true, explicit `disable.remove("MissingTranslation")`)
moved here with the strings, mirroring app/build.gradle.kts. The
integrator may drop the app-side comment about it once wiring is done.

## Open questions for the integrator
1. The version catalog has no `android-library` plugin alias; this
   build file references `libs.plugins.android.library`. Add
   `android-library = { id = "com.android.library", version.ref = "agp" }`
   to `gradle/libs.versions.toml` `[plugins]`.
2. `app/src/main/res/values/` and `values-ru/` are now empty; safe to
   delete once no sibling agent is writing into them.
3. Consumers must reference `app.onym.android.strings.R` (or the
   integrator keeps `app.onym.android.R` merging via manifest/resource
   merge in the app itself). No R-import rewrites were done here, per
   instructions.
