// :onboarding — the first-launch onboarding sequence: completion flag
// persistence + launch gate (with grandfathering), the step state
// machine, and the Compose step scaffolding. Mirrors onym-ios's
// OnymOnboarding package (PR #249); the moderation step is RESERVED
// behind a constructor flag because the moderation seat has no
// Android implementation yet.
//
// Dependency posture: this module renders the *frame* of each step.
// The middle steps' real content (discovery confirm, catalog pickers)
// lives at the app layer and arrives through OnboardingScreen's
// content slot in PR 3, so this module stays free of :discovery /
// :foundation / :transport.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // OnboardingScaffold / OnboardingScreen are Compose UI.
    alias(libs.plugins.compose.compiler)
    // NOT applied — nothing here needs them:
    //  - kotlin-serialization: the store persists one boolean, no
    //    JSON documents.
    //  - ksp: no Room / annotation processing.
}

android {
    namespace = "app.onym.android.onboarding"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // InMemoryOnboardingStore lives in testFixtures so this module's
    // own tests and :app's gate-wiring/UI tests (PR 3/4) share one
    // fake. Kotlin sources here are gated behind
    // `android.experimental.enableTestFixturesKotlinSupport=true` in
    // gradle.properties (already set for :chain / :discovery).
    testFixtures {
        enable = true
    }
}

dependencies {
    // Compose BOM pins the version-less compose artifacts below.
    // `api(platform(...))` (not implementation) so the pinning also
    // applies to the `api(...)` compose artifacts — their types appear
    // in this module's public signatures (same posture as :design).
    api(platform(libs.androidx.compose.bom))

    // api: Modifier / @Composable slots appear in OnboardingScaffold's
    // and OnboardingScreen's public signatures.
    api(libs.androidx.compose.ui)

    // implementation: MaterialTheme reads, Text/Button/TextButton —
    // no material3 type escapes this module.
    implementation(libs.androidx.compose.material3)

    // implementation: the built-in step content reuses the Settings
    // layout atoms (SettingsCard / SettingsFootnote) so onboarding
    // matches the redesigned Settings look. :design is pure UI
    // (compose + zxing-core), so this stays a cheap dependency.
    implementation(project(":design"))

    // api: StateFlow<OnboardingFlow.State> is a public property.
    // `-core` (not `-android`): no Dispatchers.Main use in this module.
    api(libs.kotlinx.coroutines.core)

    // api: DataStore<Preferences> is a public constructor parameter of
    // DataStorePreferencesOnboardingStore.
    api(libs.androidx.datastore.preferences)

    // Unit tests: flow state machine + gate truth table, plus
    // temp-file DataStore round-trips.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // JVM-only DataStore factory — the store test opens a real
    // PreferenceDataStoreFactory backed by a per-test temp file; no
    // Robolectric needed (same pattern as SeatSelectionStoreTest).
    testImplementation(libs.androidx.datastore.preferences.core)
    // This module's own tests consume its fixture
    // (InMemoryOnboardingStore).
    testImplementation(testFixtures(project(":onboarding")))
}
