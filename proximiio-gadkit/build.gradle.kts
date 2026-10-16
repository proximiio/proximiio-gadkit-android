plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

group = "io.proximi.gadkit"
version = providers.gradleProperty("gadkit.version").get()

android {
    namespace = "io.proximi.gadkit"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
    // Every public declaration is part of the API for host apps; the rest is internal. The API of
    // the Proximi GAD app is public behind the opt-in `ProximiGadAppApi`; the library uses it freely.
    explicitApi()
    compilerOptions { optIn.add("io.proximi.gadkit.ProximiGadAppApi") }
}

dependencies {
    // No public type of the library refers to the SDK or the map library. They stay `api`, so a host
    // app can use the SDK as on iOS (for example `Proximiio.logSink`) at the library's version.
    // Compose and the coroutines are in the public API: `ProximiMapHost`, the scope, `StateFlow`
    // and `Flow` of `ProximiMap`.
    api(libs.proximiio.sdk)
    api(libs.proximiio.blueiot)
    api(libs.proximiio.map)
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.haze)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
