plugins {
    // NOTE (integrator): `android-library` alias does not exist in
    // gradle/libs.versions.toml yet — add:
    //   android-library = { id = "com.android.library", version.ref = "agp" }
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.onym.android.transport"
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

    // Reusable fakes (ConfigurableInboxTransport, LoopbackInboxTransport)
    // for :app's unit + instrumented tests, formerly app/src/sharedTest.
    // NOTE (integrator): Kotlin sources in Android testFixtures are gated
    // behind `android.experimental.enableTestFixturesKotlinSupport=true`
    // in gradle.properties on AGP 8.x.
    testFixtures {
        enable = true
    }
}

dependencies {
    // `Flow` appears in the public API (MessageTransport.subscribe /
    // InboxTransport.subscribe return types) — must be `api`, not
    // `implementation`.
    // NOTE (integrator): `kotlinx-coroutines-core` alias does not exist in
    // gradle/libs.versions.toml yet — add:
    //   kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinx-coroutines" }
    // (This seam module is deliberately Android-free in its deps, so the
    // existing `kotlinx-coroutines-android` alias is NOT used here.)
    api(libs.kotlinx.coroutines.core)

    // testFixtures need no extra deps: they consume this module's main
    // types plus coroutines-core, which flows through the `api` above.
}
