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
