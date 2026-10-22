plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

group = "io.proximi.gadkit"
version = providers.gradleProperty("gadkit.version").get()

// The release goes to the repository of the SDK and the map, as in their publishing convention
// plugins. The credentials come from the environment only, never from a file.
publishing {
    repositories {
        maven {
            name = "proximiioMaven"
            url = uri("https://maven.eu.proximi.fi/releases/")
            credentials {
                username = providers.environmentVariable("MAVEN_USERNAME").orNull
                password = providers.environmentVariable("MAVEN_PASSWORD").orNull
            }
        }
    }
    publications {
        register<MavenPublication>("release") {
            artifactId = project.name
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("ProximiioGADKit for Android")
                description.set("The Guggenheim Abu Dhabi journey map on the Proximi.io SDK and Map")
                url.set("https://github.com/proximiio/proximiio-gadkit-android")
                licenses {
                    license {
                        name.set("Proprietary")
                        url.set("https://proximi.io/terms/")
                    }
                }
                developers {
                    developer {
                        id.set("proximiio")
                        name.set("Proximi.io")
                        url.set("https://proximi.io/")
                    }
                }
                scm {
                    url.set("https://github.com/proximiio/proximiio-gadkit-android")
                    connection.set("scm:git:https://github.com/proximiio/proximiio-gadkit-android.git")
                }
            }
        }
    }
}

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
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
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
    implementation(libs.androidx.lifecycle.process)
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
