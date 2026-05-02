plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "chat.onym.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "chat.onym.android"
        // EncryptedSharedPreferences requires API 23+. Picking 26 (O)
        // also unlocks AEAD ciphers + the keystore-backed master key
        // path with no compatibility shims.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Match the four Android ABIs the OnymSDK AAR ships .so files
        // for. Anything else would produce an APK that crashes on
        // System.loadLibrary("onym_sdk_jni").
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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

    // Lint hard-gates the build on missing localizations: every string
    // added to `res/values/strings.xml` must have a parallel entry in
    // every `res/values-<lang>/strings.xml`, otherwise the lint
    // `MissingTranslation` check fails. Equivalent to iOS String
    // Catalog's `state: new` warnings, but enforced as a hard gate.
    lint {
        checkReleaseBuilds = true
        abortOnError = true
        // MissingTranslation is enabled by default; explicit re-enable
        // below in case it gets suppressed in a future config sweep.
        disable.remove("MissingTranslation")
    }

    packaging {
        resources {
            // BouncyCastle ships duplicate META-INF entries that AGP
            // refuses to bundle without an explicit pickFirst rule.
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // BiometricPrompt requires a FragmentActivity host. fragment-ktx
    // pulls in the FragmentActivity class; biometric pulls in the
    // prompt itself.
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.biometric)

    implementation(libs.androidx.security.crypto)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.bouncycastle)

    implementation(libs.onym.sdk)

    // JVM unit tests. Pure-logic only — Bip39, StellarStrKey, the
    // cross-platform fixture. Anything touching EncryptedSharedPreferences
    // goes in androidTest.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented tests. Real EncryptedSharedPreferences against the
    // emulator's hardware-backed Keystore.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    // ui-test-manifest registers the test-only ComponentActivity in
    // the debug manifest so `createComposeRule()` can host content
    // without a real Activity from main code. Required by Compose UI
    // tests that don't use `createAndroidComposeRule<MyActivity>`.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
