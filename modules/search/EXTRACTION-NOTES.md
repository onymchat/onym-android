# :search extraction notes

## Files moved (plain mv, packages unchanged)

- `app/src/main/kotlin/app/onym/android/search/SearchScreen.kt`
  → `modules/search/src/main/kotlin/app/onym/android/search/SearchScreen.kt`
- `app/src/main/kotlin/app/onym/android/search/SearchViewModel.kt`
  → `modules/search/src/main/kotlin/app/onym/android/search/SearchViewModel.kt`

No unit tests existed under `app/src/test/kotlin/app/onym/android/search/`
and no `app/src/sharedTest/.../support/` fake has its subject in this
module — so no test moves and no testFixtures.

## Public symbols and their justifying consumers

- `SearchScreen` (@Composable) — called from
  `app/src/main/kotlin/app/onym/android/RootScreen.kt` (import at
  line 52, call ~line 408).
- `SearchViewModel` (class + its public constructor) — referenced by
  `app/src/main/kotlin/app/onym/android/AppDependencies.kt:59`
  (`makeSearchViewModel` factory type) and constructed in
  `app/src/main/kotlin/app/onym/android/OnymApplication.kt:856-857`;
  also the `viewModel(...)` type argument in `RootScreen.kt:403`.

## Marked internal (no outside consumer per repo-wide grep of app/src + modules/)

- `MessageSearchResult` (top-level data class) — only consumed by
  `SearchScreen`/`SearchViewModel` inside this module.
- `SearchViewModel.query`, `SearchViewModel.results`,
  `SearchViewModel.onQueryChange` — only `SearchScreen` (same module)
  reads them; `RootScreen` merely constructs the VM and hands it to
  `SearchScreen`. Making these internal also lets `MessageSearchResult`
  be internal (a public `results` would illegally expose it).

`SearchResultRow` and `SearchEmptyState` were already `private`.

## Dependency decisions

api:
- `project(":chats-core")` — `MessageRepository` is a public
  constructor parameter of `SearchViewModel`.
- `project(":group")` — `GroupRepository` is a public constructor
  parameter of `SearchViewModel`.
- `platform(libs.androidx.compose.bom)` + `libs.androidx.compose.ui` —
  exported `SearchScreen` is `@Composable` (annotation from
  compose-runtime, an api dep of ui).
- `libs.androidx.lifecycle.viewmodel.compose` — `ViewModel` is the
  public supertype of `SearchViewModel` (same convention as :group,
  :inbox, :identity).

implementation:
- `project(":strings")` — `R.string` only in composable bodies.
- `libs.androidx.compose.material3` — widgets body-level only.
- `libs.androidx.lifecycle.runtime.compose` —
  `collectAsStateWithLifecycle` body-level only.
- `libs.kotlinx.coroutines.core` — StateFlow/Job internal-only after
  the visibility pass above.

## Trims (listed in the task but not needed)

- `project(":identity")` — no identity type is referenced by either
  file (owner-scoping happens inside `MessageRepository.search`).
- `project(":design")` — no design token/component used; the screen is
  plain Material3.
- `material-icons-extended` (used by app) — `Icons.Filled.Search` is in
  material-icons-core, which material3 already provides via api.
- No KSP / serialization / Room / navigation — nothing in this module
  uses them.

## Open questions for the integrator

- Same note as :design — the plugin is applied as
  `id("com.android.library")` because the catalog has no
  `android-library` alias; consider adding one and switching.
- App consumers (`RootScreen`, `OnymApplication`, `AppDependencies`)
  need `implementation(project(":search"))` wired in
  `app/build.gradle.kts` plus the `include(":search")` in
  `settings.gradle.kts` (not touched here by instruction).
