// :design — brand primitives (OnymBrand tokens/theme/mark), the
// Settings layout atoms, and the QR renderer. Pure UI; no app state,
// no network, no storage.
plugins {
    // No `android-library` alias exists in gradle/libs.versions.toml
    // (only `android-application`). Applying by id without a version
    // is safe: the root build's `alias(libs.plugins.android.application)
    // apply false` already puts AGP on the build classpath, so this
    // resolves to the same 8.11.2. Integrator may want to add an
    // `android-library` alias to the catalog and switch this line.
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.onym.android.design"
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
}

dependencies {
    // Compose BOM pins the versions of the version-less compose
    // artifacts below. `api(platform(...))` (not implementation) so
    // the pinning also applies to the `api(...)` compose artifacts —
    // their types appear in this module's public signatures.
    api(platform(libs.androidx.compose.bom))

    // Public API surface: Modifier / Dp / @Composable (ui + its api
    // deps ui-unit and runtime) and Color / ImageVector (ui-graphics)
    // appear in exported function signatures (OnymTheme, OnymMark,
    // SettingsRow, OnymQrCode, ...), so consumers need these on their
    // compile classpath -> api.
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)

    // Internal use only (MaterialTheme reads, Text/Icon, color
    // schemes). material3's own api deps supply foundation,
    // animation-core, and material-icons-core (the AutoMirrored
    // KeyboardArrowRight chevron) to this module's compile classpath.
    implementation(libs.androidx.compose.material3)

    // ZXing core — QR bit-matrix encoder consumed by OnymQrCode's
    // private encodeQrMatrix(); no ZXing type escapes this module.
    implementation(libs.zxing.core)
}
