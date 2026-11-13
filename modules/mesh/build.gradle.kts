// :mesh — the Bluetooth LE mesh (docs/mesh-ble-v1.md in onym-ios): rotating
// recipient tags, v1 and compact frames, reassembly, a receive-only
// InboxTransport, the broadcast outbox, and the Android radio (scanner +
// non-connectable advertiser). Wire-compatible with iOS's OnymMesh; both
// test against the same mesh-v1-vectors.json.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.onym.android.mesh"
    compileSdk = 36

    defaultConfig {
        // Advertising sets (non-connectable, non-scannable legacy
        // advertisements) arrived in API 26.
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":transport"))
    api(libs.kotlinx.coroutines.core)
    // X25519 + HKDF, the same library :identity derives inbox keys with.
    api(libs.bouncycastle)
    // JsonObject parsing for the binary ↔ JSON sealed-envelope transcode
    // (no serialization plugin needed for JsonElement).
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
}
