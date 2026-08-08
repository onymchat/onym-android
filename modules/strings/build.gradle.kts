plugins {
    // NOTE: the catalog currently only defines `android-application`.
    // The integrator must add
    //   android-library = { id = "com.android.library", version.ref = "agp" }
    // to gradle/libs.versions.toml [plugins] for this alias to resolve.
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.onym.android.strings"
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

    // Lint hard-gates the build on missing localizations: every string
    // added to `res/values/strings.xml` must have a parallel entry in
    // every `res/values-<lang>/strings.xml`, otherwise the lint
    // `MissingTranslation` check fails. The gate lives here because the
    // strings live here.
    lint {
        checkReleaseBuilds = true
        abortOnError = true
        // MissingTranslation is enabled by default; explicit re-enable
        // below in case it gets suppressed in a future config sweep.
        disable.remove("MissingTranslation")
    }
}

dependencies {
    // Intentionally empty: this module ships only string resources
    // (res/values{,-ru}/strings.xml) and contains no Kotlin/Java code,
    // so it needs no runtime, test, or project dependencies.
}
