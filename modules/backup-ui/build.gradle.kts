// :backup-ui — the device-backup seat's Compose surfaces: the
// consent/enrolment screen, the Settings status screen, and the
// restore-from-backup flow. Mirrors onym-ios's OnymBackupUI package.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.onym.android.backup.ui"
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
    // api: SealedSnapshot / BackupTerms / BackupRepository types
    // appear in this module's public signatures.
    api(project(":backup"))
    // api: SeatEntitlement / ServiceEntitlement types appear in the
    // purchase-gated status surfaces.
    api(project(":foundation"))

    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
