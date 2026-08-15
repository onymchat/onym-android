pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // OnymSDK Kotlin — static Maven repo on the releases branch of
        // onymchat/onym-sdk-kotlin. Provides chat.onym:onym-sdk:<vX.Y.Z>
        // (the AAR with classes.jar + jni/<abi>/*.so for all four
        // Android ABIs).
        maven {
            name = "OnymSDK"
            url = uri("https://raw.githubusercontent.com/onymchat/onym-sdk-kotlin/releases/")
            content {
                includeGroup("chat.onym")
            }
        }
    }
}

rootProject.name = "onym-android"
include(":app")

// Extracted library modules live under modules/ (flat Gradle names,
// nested directory layout).
include(":strings")
project(":strings").projectDir = file("modules/strings")
include(":design")
project(":design").projectDir = file("modules/design")
include(":foundation")
project(":foundation").projectDir = file("modules/foundation")
include(":transport")
project(":transport").projectDir = file("modules/transport")
include(":chain")
project(":chain").projectDir = file("modules/chain")
include(":transport-nostr")
project(":transport-nostr").projectDir = file("modules/transport-nostr")
include(":transport-blossom")
project(":transport-blossom").projectDir = file("modules/transport-blossom")
include(":identity")
project(":identity").projectDir = file("modules/identity")
include(":persistence")
project(":persistence").projectDir = file("modules/persistence")
include(":group")
project(":group").projectDir = file("modules/group")
include(":chats-core")
project(":chats-core").projectDir = file("modules/chats-core")
include(":inbox")
project(":inbox").projectDir = file("modules/inbox")
include(":search")
project(":search").projectDir = file("modules/search")
include(":discovery")
project(":discovery").projectDir = file("modules/discovery")
include(":onboarding")
project(":onboarding").projectDir = file("modules/onboarding")
