pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.eu.proximi.fi/releases/") {
            content {
                includeGroup("io.proximi.sdk")
                includeGroup("io.proximi.map")
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "proximiio-gadkit-android"
// The project name is the artifact id: a host build with `includeBuild` resolves
// `io.proximi.gadkit:proximiio-gadkit` to this project.
include(":proximiio-gadkit")
