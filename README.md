# ProximiioGADKit for Android

ProximiioGADKit is an Android library with the museum map and the journey (map and list) screens of
the Proximi GAD app (Guggenheim Abu Dhabi) and the wristband binding, built on the Proximi.io Android
SDK and Map. It is the Android counterpart of `proximiio/proximiio-gadkit-ios`. The Proximi GAD
Android app uses the library the same way a host app does.

No version is tagged yet. The first tag is `0.11.0`, with the behaviour of the iOS tag `0.11.0`.
Until then the API can change with any commit.

## Adding the library

Add this repository to the host app as a git submodule, for example at `gadkit`, and include its
build in `settings.gradle.kts`:

```kotlin
includeBuild("gadkit")
```

Then depend on the library without a version; Gradle resolves it to the included build:

```kotlin
dependencies {
    implementation("io.proximi.gadkit:proximiio-gadkit")
}
```

The library brings the Proximi.io SDK, the Map library and Compose as `api` dependencies.

## Starting

```kotlin
val configuration = GadConfiguration(
    apiBaseUrl = "https://api.example.com",
    applicationToken = applicationToken,
    relayApiBaseUrl = "https://relay-api.example.com",
    relayApiAppToken = relayApiAppToken,
)
val wristband = WristbandSession(context, configuration, scope)
val venue = VenueService.start(context, configuration, wristband.binding)
```

## Tests

```
./gradlew :proximiio-gadkit:testDebugUnitTest
```
