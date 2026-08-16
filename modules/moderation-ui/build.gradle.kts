// :moderation-ui — the moderation seat's Compose surfaces: the
// full-screen Banned and Verification-required gates, the consent
// step content (onboarding step body + full-screen re-consent host),
// and the ModerationGateFlow combiner that folds the repositories'
// snapshots into the one RootGate the app root switches on. Mirrors
// onym-ios's OnymModerationUI package.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.onym.android.moderation.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    // Hard i18n gate, same as :strings / :onboarding: every string
    // this module adds must land in every `res/values-<lang>/`,
    // otherwise lint MissingTranslation fails. App lint does not check
    // library modules' resources, so the gate must live here too.
    lint {
        checkReleaseBuilds = true
        abortOnError = true
        disable.remove("MissingTranslation")
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
    // api: BanState / CheckRequiredReason / GateStatus and the
    // repositories appear in this module's public signatures.
    api(project(":moderation"))

    api(platform(libs.androidx.compose.bom))
    // api: Modifier / @Composable appear in public signatures.
    api(libs.androidx.compose.ui)
    // implementation: MaterialTheme reads, Text/Button/Scaffold — no
    // material3 type escapes this module.
    implementation(libs.androidx.compose.material3)
    // BackHandler in the in-app markdown document viewer (pop one
    // followed document per system back).
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":moderation")))
}
