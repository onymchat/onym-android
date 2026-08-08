// :search — the Search tab: full-text search across the active
// identity's chat messages (SearchScreen + SearchViewModel). Android
// twin of iOS `SearchView`.
plugins {
    // No `android-library` alias exists in gradle/libs.versions.toml
    // (only `android-application`). Applying by id without a version
    // is safe: the root build's `alias(libs.plugins.android.application)
    // apply false` already puts AGP on the build classpath, so this
    // resolves to the same version. Integrator may want to add an
    // `android-library` alias to the catalog and switch this line.
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.onym.android.search"
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
    // ---- project ----

    // api: MessageRepository is a public constructor parameter of
    // SearchViewModel (constructed by app's OnymApplication), so
    // consumers need the type on their compile classpath.
    api(project(":chats-core"))

    // api: GroupRepository is a public constructor parameter of
    // SearchViewModel — same reasoning as :chats-core.
    api(project(":group"))

    // implementation: R.string references live only in SearchScreen's
    // composable body.
    implementation(project(":strings"))

    // ---- external ----

    // api(platform): the BOM must also pin the api'd compose artifact
    // below on consumers' classpaths.
    api(platform(libs.androidx.compose.bom))
    // api: the exported SearchScreen is @Composable — the annotation
    // lives in compose-runtime, an api dependency of ui, so consumers
    // need it on the compile classpath to call it.
    api(libs.androidx.compose.ui)
    // implementation: Material3 widgets and layout/foundation types are
    // all body-level. material3's api dep material-icons-core supplies
    // Icons.Filled.Search — no icons-extended needed.
    implementation(libs.androidx.compose.material3)

    // api: public SearchViewModel extends androidx.lifecycle.ViewModel
    // (public supertype) — viewmodel-compose carries lifecycle-viewmodel
    // (ViewModel + viewModelScope on 2.8).
    api(libs.androidx.lifecycle.viewmodel.compose)
    // implementation: collectAsStateWithLifecycle in SearchScreen's body.
    implementation(libs.androidx.lifecycle.runtime.compose)

    // implementation: StateFlow/Job/launch/delay are internal-only —
    // SearchViewModel's flow properties are `internal`, so no coroutines
    // type escapes the module's public API.
    implementation(libs.kotlinx.coroutines.core)
}
