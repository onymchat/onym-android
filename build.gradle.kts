// Top-level build file. Plugins are declared with `apply false` so each
// subproject can opt in via its own `plugins {}` block. Versions live in
// gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
}
