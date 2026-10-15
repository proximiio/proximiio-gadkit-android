# ProximiioGADKit for Android

ProximiioGADKit is an Android library with the museum map and the journey (map and list) screens of
the Proximi GAD app (Guggenheim Abu Dhabi) and the wristband binding, built on the Proximi.io Android
SDK and Map. It is the Android counterpart of `proximiio/proximiio-gadkit-ios`: an Android tag
`X.Y.0` has the behaviour of the iOS tag `X.Y.0`. A host app with its own QR scanner passes the
scanned wristband code to `WristbandSession.connect(code)`. The Proximi GAD Android app uses the
library the same way a host app does.

| Artifact | Content |
|---|---|
| `io.proximi.gadkit:proximiio-gadkit` | Screens, design system, SDK start, wristband session, notifications, field log. |

## Requirements

- Android 8.0 or later (`minSdk` 26), `compileSdk` 37. Kotlin 2.2 and Jetpack Compose.
- Built and tested with Android Gradle Plugin 8.13, Gradle 8.14 and JDK 17.
- Read access to this repository. The Proximi.io SDK and Map are on
  `https://maven.eu.proximi.fi/releases/` (no credentials).

## Adding the library

Add this repository to the host app as a git submodule at a tag, for example at `gadkit`:

```
git submodule add https://github.com/proximiio/proximiio-gadkit-android.git gadkit
git -C gadkit checkout 0.12.0
```

In the host app's `settings.gradle.kts`, include the library's build and add the Proximi.io
repository:

```kotlin
dependencyResolutionManagement {
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

includeBuild("gadkit")
```

Then depend on the library without a version. Gradle resolves it to the included build:

```kotlin
dependencies {
    implementation("io.proximi.gadkit:proximiio-gadkit")
}
```

- The versions are the git tags of this repository. Before 1.0, a minor version can change the API.
- The library depends on SDK `6.0.0-beta.23` and Map `6.0.0-beta.21` and exposes them, with Compose,
  as `api` dependencies. A host app that also declares these artifacts must use the same versions.
- The library ships its own R8 consumer rules. The host app needs no rule for it.
- With the Gradle configuration cache on, set `kotlin.internal.collectFUSMetrics=false` in the host
  app's `gradle.properties`. Otherwise the Kotlin Gradle plugin registers its build statistics
  service in both builds, and the configuration fails ("Cannot set the value of a property of type
  BuildFusService").

## Host app setup

### Manifest

The library's manifest adds these permissions to the host app:

| Permission | Why |
|---|---|
| `INTERNET` | The venue data, the relay-api and visitor reporting. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `WAKE_LOCK` | The SDK keeps positioning alive with a foreground service of type `location` and its own notification. |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | The relay-api needs a fix for a wristband take-over. The foreground service needs the location type. |
| `POST_NOTIFICATIONS` | The journey notification, the geofence and next-stop notifications. |
| `CAMERA` (`android.hardware.camera` not required) | The QR scanner of `WristbandPairingScreen`. |

The manifest also removes `ACCESS_BACKGROUND_LOCATION`: the foreground service keeps the process
alive, so background location is never requested. The phone scans nothing (relay-only), so no
Bluetooth permission is requested. Keep the same `tools:node="remove"` entry in the host app's
manifest, so that no other library adds the permission back.

The field log has a `FileProvider` with the authority `<applicationId>.gadkit.fieldlog`.

### Permissions at run time

- Location: request `ACCESS_FINE_LOCATION` (with `ACCESS_COARSE_LOCATION`) before
  `VenueService.start`. iOS `VenueService.start` asks itself; on Android a request needs the host
  app's activity.
- Notifications (Android 13 and later): `AskNotificationPermission(isNeeded)` asks once per process.
  `VisitScreen` calls it when it opens. Call it once a wristband session is active, as the Proximi
  GAD app does:

```kotlin
val state by wristband.state.collectAsStateWithLifecycle()
AskNotificationPermission(isNeeded = state.session != null)
```

### Languages

The library ships English and Arabic strings. Declare both languages in the host app's
`localeConfig`, so that the per-app language applies to the library screens too. The screens
support right-to-left layouts.

### Fonts

Guggenheim Sans Display is a font resource of the library (`GuggenheimSansDisplay`). Nothing needs
registering.

## Starting

```kotlin
val configuration = GadConfiguration(
    apiBaseUrl = "https://api.example.com",
    applicationToken = applicationToken,
    relayApiBaseUrl = "https://relay-api.example.com",
    relayApiAppToken = relayApiAppToken,
)
// Once per process, for example in an activity-scoped ViewModel. It follows the binding state.
val wristband = WristbandSession(context, configuration, viewModelScope)
// After the location request, at launch, before the first map screen.
val venue = VenueService.start(context, configuration, wristband.binding)
```

- `WristbandSession` confirms a session the SDK stored at the last launch with the relay-api, and
  follows the binding state in the scope you pass. Create one per process.
- `VenueService.start` creates the SDK (`ProximiioConfiguration.relayOnly`, no fallback host),
  reads the permissions, authenticates, starts positioning, loads the route network and attaches
  the wristband's position provider. Visitor reporting is on.
- After the start it fetches the organisation's map style in the background into the cache
  directory. A map screen then starts on that style instead of an empty one.
- On the first map screen after an install, MapLibre downloads the basemap tiles while the map is on
  screen. Until they arrive, the map shows the style's white background.
- `venue.sdk` is the app's only `Proximiio` instance. Other features of the host app, such as the
  audio guide, use it. Do not create a second `Proximiio`.
- The library contacts only `apiBaseUrl` and the relay-api host.
- `GadConfiguration` throws `IllegalArgumentException` when `relayApiBaseUrl` has no host.
  `toString()` leaves the tokens out; `secrets` lists them for `FieldLog.start`.

### Settings

Every time and distance of the screens is in `GadSettings`. Set them in the configuration, or later
in `venue.settings`. A screen reads them when it opens, so a change applies to the next screen:

```kotlin
val configuration = GadConfiguration(
    apiBaseUrl, applicationToken, relayApiBaseUrl, relayApiAppToken,
    settings = GadSettings(offRouteMeters = 50.0, trackingLostAfter = 90.seconds),
)
```

| Setting | Default | Effect |
|---|---|---|
| `trackingLostAfter` | 110 s | Time without positioning before "We've lost your place". The SDK reports positioning unavailable about 10 s after the last fix: about 2 min. |
| `trackingStoppedAfter` | 170 s | Time without positioning before "Your wristband has stopped responding" (about 3 min). |
| `artworkAdvanceDelay` | 2 s | From the arrival at an artwork (its geofence) to the route to the next stop. |
| `outOfOrderDwell` | 0 s | Time inside another artwork's geofence before it counts as visited out of order. |
| `amenityArrivalMinimumMeters`, `amenityArrivalMaximumMeters`, `amenityArrivalDwell` | 5 m, 10 m, 5 s | An amenity is reached within 2.5 × the position accuracy, within these bounds, for this time. |
| `stopNearbyMeters` | 5 m | The "next stop is less than … m away" notification. |
| `offRouteMeters`, `offRouteAfter` | 30 m, 300 s | "You've left your route": this far from the route, or off it this long. |
| `routeSnapMeters` | 0.5 m | The dot is drawn on the route within this distance. Display only. |
| `followZoom` | 21 | Zoom of "Centre on me" and of the first position. |
| `liftRideDwell`, `liftExitFixes` | 3 s, 2 | Time of fixes inside a lift outline before "In the lift" or "Level up", and the fixes outside that end the ride. |
| `uncertainAccuracyMeters`, `locationQuestionQuietPeriod` | 8 m, 120 s | "Probably at …": an uncertain position near the next stop, and how long an answered prompt stays quiet. |
| `firstFixWait`, `lateOrderWindow` | 5 s, 60 s | A new personalised journey waits this long for a fix before it is ordered without one, and the first fix within the window orders it again. |
| `journeyNotificationMinimumInterval` | 5 s | The shortest gap between two updates of the instruction in the journey notification. |

Times are `kotlin.time.Duration`, distances metres.

### Notifications

Notifications for geofence enter and exit:

```kotlin
scope.launch { GeofenceNotifier(context).run(venue.sdk) }
```

- Every geofence with a name posts one: the title is the name, the text "You entered this area." or
  "You left this area.". A notification replaces the earlier one of the same geofence and direction.
  Privacy zones post nothing.
- During a visit, `VisitScreen` posts "The next stop of your journey is less than 5 m away." once per
  stop.
- Both use the channel `alerts` (high importance, no sound). The journey notification uses the
  channel `visit`; the SDK's positioning service has its own channel.
- Without the notification permission nothing is shown.

## Wristband

The host app scans the wristband's QR code, or the visitor types the code printed on it. Pass the
result to `connect(code)`:

```kotlin
scope.launch {
    try {
        wristband.connect(scannedCode)
    } catch (error: BindingError) {
        errorText = WristbandCopy.message(error).resolve(context)   // English or Arabic
    }
}
```

- `connect(code)` trims the code and sends it to the relay-api, which resolves every label format.
  It returns when the session is active and throws `BindingError` otherwise
  (`BindingError.InvalidTagId` for an empty code).
- When the relay-api needs the phone's location for a take-over, the system location dialog comes
  first. Compose `WristbandLocationDialogHost(wristband)` on the screen that calls `connect(code)`;
  without it the bind goes out without asking.
- A wristband that another phone follows is taken over when the relay-api allows it. The other
  phone's session ends.
- `isActive`, `state` (`StateFlow<BlueiotBindingState>`) and `endNotice` describe the session.
  Positions arrive only while it is active. `end()` ends the session on the relay-api.
- When the session ends during a visit, `VisitScreen` shows "wristband no longer connected" with
  "Connect a wristband". That button opens the host app's screen:

```kotlin
VisitScreen(
    venue, wristband, journey,
    onClose = { isVisiting = false },
    wristbandConnectionScreen = { close -> MyWristbandScanner(onFinish = close) },  // calls connect(code)
)
```

Without `wristbandConnectionScreen`, the button opens `WristbandPairingScreen`, the pairing flow of
the Proximi GAD app. When the screen closes with an active session, `VisitScreen` reopens the
wristband's position stream; without one, the "wristband no longer connected" sheet shows again.

## Screens

| Composable | Content |
|---|---|
| `MuseumMapScreen(venue, onBack)` | The museum map: floors, amenities, the visitor's position, a route to a tapped place, the accessible routes button. |
| `VisitScreen(venue, wristband, journey, onClose, kind, onVisitEvent, wristbandConnectionScreen)` | The journey: map and list with a toggle, skip and undo, the stop card, tracking alerts, the journey notification. `kind` is `JourneyKind.PERSONALISED` (default) or `CURATED`. |
| `WristbandPairingScreen(flow, onFinish)` | Optional: the pairing flow of the Proximi GAD app, with its QR scanner. Create the flow with `WristbandPairingFlow.of(wristband, scope)`. |
| `VisitPlanPicker(places, onStart, onCancel)` | Choosing stops by hand. |
| `JourneyReadyScreen(meta, isAtMuseum, onStart, onBack)` | "Journey ready" (02.10A). |
| `ManageJourneySheet(title, summary, onEdit, onCraftNew, onDelete, onClose)` | "Manage journey" (02.11). Each action runs once the sheet has gone. |

`VisitScreen` keeps its state while the activity is recreated, for example after a language change.
It ends the visit's work (notification, tracking alerts, navigator) when it leaves the composition
for good. `onClose` is called after the visitor confirmed leaving, or after the journey is complete.

A journey from Proximi.io POI ids, for example from the audio guide:

```kotlin
val places = venue.places(language)        // the app's language tag, for example "ar"
val galleries = venue.galleries(language)
val journey = VisitPlan.journey(poiIds, places, galleries)
```

`VisitStore.of(context)` keeps the journey in progress and its `VisitMeta` across launches
(`journey`, `meta`, `save`, `saveMeta`). `VisitScreen` saves the journey on every change. Call
`VisitStore.of` off the main thread the first time: it reads the disk.

### Personalised and curated journeys

A journey the visitor built with the guide is `PERSONALISED`; a tour by the museum's curators is
`CURATED` (Figma "Personalised" and "Guided"):

| | `PERSONALISED` | `CURATED` |
|---|---|---|
| Stop card | Names the gallery of the next stop until the visitor is in it, then the artwork. | Names the artwork. |
| List | Stops under gallery headers. | Stops without headers. |
| Skip | The visitor can skip an artwork, also one no accessible route reaches. | No skip. |
| Order | Ordered floor by floor and gallery by gallery from the visitor, at the start and after a detour or an artwork visited out of order. | The curators' order. An artwork visited out of order is marked visited; the rest keep their order. |

The map and the background are the same for both. `VisitMeta.kind` stores the kind with the journey
for the host app's own screens.

## The journey list

A row shows the artist and "AR" and audio badges from the artwork's POI `properties.metadata`.
Without `artist_name` the row shows the level.

| Key | Value | Shows |
|---|---|---|
| `artist_name` | String, or an i18n object like `title_i18n` | The artist under the title. |
| `has_ar` | `true`, `"true"` or a non-zero number | "AR". |
| `has_audio` | `true`, `"true"` or a non-zero number | A note. |
| `gallery_id` | The `remote_id` of a gallery geofence | The stop's gallery header. |

The header of a stop is its gallery by `gallery_id`, named by the gallery geofence with that
`remote_id`; else the gallery outline it lies in; else its level ("Level 4").

## Accessibility

The host app passes the visitor's accessibility needs to the map and journey screens. Every route
then avoids what they name, and a change routes the route on screen again:

```kotlin
CompositionLocalProvider(LocalRouteAccessibility provides RouteAccessibility(avoidsStairs = true, avoidsRevolvingDoors = true)) {
    HostContent()
}
```

| Need | Avoids, where the venue data marks it |
|---|---|
| `avoidsStairs` | Level changers with `type` `staircase`. |
| `avoidsEscalators` | Level changers with `type` `escalator`. |
| `avoidsRevolvingDoors` | Path pieces within `radius` of a `door` point feature. |
| `avoidsInaccessiblePaths` | Routing paths with `narrowPath: true`. |

- The GAD venue data on 2026-10-14 has staircases and elevators. It has no escalators, no `door`
  features and no `narrowPath` paths, so only `avoidsStairs` changes routes until the data has them.
- `RouteAccessibility.routeOptions` is the SDK's `RouteOptions` for the needs, for routes the host app
  computes itself.
- The museum map and the journey map have an accessible routes button (a wheelchair, blue while on).
  While it is on, routes avoid all four (`RouteAccessibility.ACCESSIBLE`), whatever the host app
  provides. Its state is a Boolean in the shared preferences file `proximiio_gadkit` under
  `ProximiioGADKit.accessibleRoutes` (`RouteAccessibility.PREFERENCES_FILE`,
  `ACCESSIBLE_ROUTES_KEY`), for both maps and later launches. `VisitScreen` reports
  `VisitEvent.AccessibleRoutesChanged`.
- When no route meets the needs, the museum map shows "No route from here with your accessibility
  settings." In a journey, the stop card shows "No route to this stop with your accessibility
  settings." and the stop keeps its place; in a personalised journey the visitor can skip it in the
  list.

## Events from VisitScreen

`VisitScreen` performs every action itself. The host app receives a `VisitEvent` for each visitor
action and each journey change, on the main thread:

```kotlin
VisitScreen(venue, wristband, journey, onClose = onClose, onVisitEvent = { event ->
    when (event) {
        is VisitEvent.SkipRequested -> log("Swiped ${event.stop.title}")
        is VisitEvent.StopSkipped -> log("Skipped ${event.stop.title}")
        is VisitEvent.ArtworkReached -> log("At ${event.stop.title}")
        else -> Unit
    }
})
```

| Event | When |
|---|---|
| `ModeChanged(VisitMode)` | The Map / List toggle. |
| `SkipRequested(JourneyStop)` | A swipe on a stop in the list, or its "Skip" TalkBack action. "Skip artwork?" follows. |
| `StopSkipped(JourneyStop)` | "Skip artwork" confirmed. |
| `SkipCancelled(JourneyStop)` | "Keep it". |
| `SkipUndone(JourneyStop)` | "Undo skip" in the list. |
| `AmenitySelected(VenuePlace)` | An amenity picked on the map. A card shows it. |
| `DetourStarted(VenuePlace)` | "Change your route?" confirmed, or the route to the Welcome Desk from a tracking alert. |
| `JourneyResumed` | "Resume your Journey" or "Show the route from here". |
| `Recentred` | "Centre on me". |
| `AccessibleRoutesChanged(Boolean)` | The accessible routes button turned on (`true`) or off. |
| `ExitRequested` | Back during a visit in progress. "Leave" or "Carry on" follows. |
| `ExitConfirmed` | "Leave". `onClose` follows. |
| `ExitCancelled` | "Carry on". |
| `StopNearby(JourneyStop)` | The active stop is less than 5 m away on the visitor's level. Once per stop per visit. |
| `ArtworkReached(JourneyStop)` | The visitor entered the artwork geofence of the active stop, or stands in its area. The stop is done 2 s later, when the event arrives, and the route leads to the next stop. |
| `ArtworkVisitedOutOfOrder(JourneyStop)` | The visitor stayed at an artwork further down the journey. The stop is done and the stops ahead are ordered again. |
| `Journey(JourneyEvent)` | Every event of the journey navigator: `LegChanged`, `StopSkipped`, `Rerouted`, `DetourStarted`, `StopReached` (detours only), `JourneyFinished` and others. The navigator does not report artworks reached: use `ArtworkReached`. |

## Journey notification

The iOS Live Activity is an ongoing notification on Android: the current step, "Stop N of M", the
next instruction and the level. `VisitScreen` posts, updates and removes it (channel `visit`). It
needs the notification permission.

## Design system

`Palette`, `GadTextStyle`, `GuggenheimSansDisplay`, `PrimaryButton`, `GhostButton`,
`TextActionButton`, `OutlineBadge`, `Modifier.glassSurface`, `ProvideGlassBackdrop`,
`Modifier.glassBackdrop`, `AuroraBox`, `BackButton`, `OverlaySheet` and `Chip` are the Figma tokens and
components, for the host app's own screens (package `io.proximi.gadkit.designsystem` and
`io.proximi.gadkit.ui`).

## Field log

`FieldLog` writes positions, tracking health, geofence, journey and binding events to
`files/fieldlog/gad-debug.log` in app-private storage, and to Logcat with the tag `GAD`. It is off by
default. Turn it on at launch, before `VenueService.start`, in test builds only:

```kotlin
FieldLog.start(context, configuration.secrets)   // the tokens are replaced by <redacted>
Proximiio.logSink = { entry -> FieldLog.log("sdk ${entry.category} ${entry.level.label.lowercase()}: ${entry.message}") }
```

`FieldLog.share(context)` opens the share sheet with the file. The file is cut to its last 4 MB when
it passes 8 MB.

## Debug builds

In a debuggable host build, `DebugLaunch` reads intent extras. The SDK-dependent extras take effect
when the host app calls `run` after `VenueService.start`:

```kotlin
val options = DebugLaunch.read(activity)   // null in a build that is not debuggable
if (options != null) DebugLaunch.run(options, venue, venue.places(language))
```

```
adb shell am start -n <applicationId>/<activity> --es gad.journey <journey id> --es gad.journeySpeed 2 --ez gad.journeyLoops true
```

| Extra | Effect |
|---|---|
| `gad.journey <journey id>` (alias `gad.journeyPlayback`) | Plays a journey stored on Proximi.io instead of the wristband. |
| `gad.journeySpeed <x>` | Playback speed. Default 1. |
| `gad.journeyLoops true` | Restarts playback after the last waypoint. |
| `gad.listJourneys true` | Logs the organisation's places and journeys with their ids. |
| `gad.routeTo <title>` | `MuseumMapScreen` routes to the first place whose title matches, after the first position. |
| `gad.mapGalleryFills true` | `MuseumMapScreen` fills every gallery. |
| `gad.visitList true` | `VisitScreen` opens in List mode. |
| `gad.visitAutoAdvance true` | A reached detour ends 5 m after the visitor leaves it, without "Resume your Journey". |
| `gad.visitKind personalised\|curated` | Sets `Options.visitKind`. The host app passes it to `VisitScreen`. |
| `gad.routeAvoid stairs,escalators,revolvingDoors,inaccessiblePaths` | Sets `Options.routeAccessibility`. The host app provides it with `LocalRouteAccessibility`. |

`DebugLaunch` also reads `gad.visit` (`Options.visitStops`) and `gad.hubPage` (`Options.hubPage`).
The library does not act on them; the Proximi GAD app reads them. `VenueService.playJourney` plays a
journey in a debuggable build only.

Sandbox data (GAD Sandbox organisation):

| Kind | Value |
|---|---|
| Hosts | `https://api.proximi.fi`, `https://relay-api-sandbox.proximi.fi` |
| Test wristbands | `1000054522`, `1000045550`, `1000045662` |

Journeys of the GAD Sandbox organisation on 2026-10-14, for `gad.journey <journey id>`. Each one
passes the SDK's journey validation. `gad.listJourneys` logs the current list.

| Journey | Id | Waypoints | Path length |
|---|---|---|---|
| Guggenheim walk 1 | `7405e6e6-d9f6-4146-adda-de6adbf4a8a1:c77dfca6-07fd-4c19-bf76-fb92bfdf361c` | 21 | 131 m |
| Guggenheim walk 2 | `7405e6e6-d9f6-4146-adda-de6adbf4a8a1:8c42ce38-c787-4adf-b424-ae7a33a75cce` | 8 | 153 m |
| Guggenheim walk 3 | `7405e6e6-d9f6-4146-adda-de6adbf4a8a1:5bea514e-ad20-416a-bccc-dda10ea7e880` | 18 | 235 m |
| Guggenheim walk 4 | `7405e6e6-d9f6-4146-adda-de6adbf4a8a1:57fbece2-7e6f-4c60-a00d-68f9e8e8c0e3` | 12 | 242 m |
| Guggenheim walk 5 | `7405e6e6-d9f6-4146-adda-de6adbf4a8a1:09ecc5b7-ec2b-4362-849b-d9cc8f670919` | 17 | 200 m, two floors |
| Guggenheim walk 6 | `7405e6e6-d9f6-4146-adda-de6adbf4a8a1:0bc12bdf-c0e5-491e-b7af-ec743831b85a` | 3 | 98 m |
| Guggenheim walk 7 | `7405e6e6-d9f6-4146-adda-de6adbf4a8a1:5bebd389-4f28-4e53-814f-b1ef829fcc3c` | 26 | 131 m |
| Guggenheim walk Test | `7405e6e6-d9f6-4146-adda-de6adbf4a8a1:06ffcc22-d033-4186-af80-c65499902cd4` | 4 | 107 m |
| Guggenheim Highlighted Tour | `7405e6e6-d9f6-4146-adda-de6adbf4a8a1:6718c69c-37e6-4a28-bd44-00aa35c46b2f` | 25 | 495 m |
| DCT Office walk | `7405e6e6-d9f6-4146-adda-de6adbf4a8a1:99a3beee-65cc-43c4-9b35-9e6dbee06e09` | 8 | 34 m, at the DCT office, not the museum |
| Test DCT Walk_Pranav | `7405e6e6-d9f6-4146-adda-de6adbf4a8a1:b9aab2da-09fc-4371-bb46-7071fc010c2f` | 3 | 14 m, at the DCT office, not the museum |

## Privacy

- The SDK reports the visitor to `apiBaseUrl`: a visitor id, positions at most every 10 s (or after
  5 m), and geofence enter and exit events. The Android SDK `6.0.0-beta.23` sends positions and
  events in batches every 60 s. Without network they wait on the device and go out once the network
  is back. The client requires no consent prompt for it.
- The Play Console Data safety form of the host app declares precise location and a device
  identifier (the anonymous visitor id), sent to Proximi.io, not shared and not used for tracking.
- The library asks for no permission itself; the host app requests location and notifications.

## Differences from the iOS package

| iOS | Android | Note |
|---|---|---|
| `GADConfiguration(apiBaseURL: URL, …, relayAPI: BlueiotCloudRelayEndpoint, …)` | `GadConfiguration(apiBaseUrl: String, …, relayApiBaseUrl: String, …)` | URLs are strings; an invalid relay-api URL throws. |
| `GADSettings` (`var`s, seconds as `TimeInterval`) | `GadSettings` (data class, `Duration`) | `offRouteSeconds` is `offRouteAfter`; `liveActivityMinimumInterval` is `journeyNotificationMinimumInterval`. |
| `WristbandSession(configuration:)` + `run()` | `WristbandSession(context, configuration, scope)` | It follows the binding state in `scope`; there is no `run()`. |
| `VenueService.start` asks for location | The host app asks for location first | Android needs an activity for the dialog. |
| `VisitView(venue:wristband:journey:kind:onClose:)` with the modifiers `onVisitEvent`, `wristbandConnectionScreen` | `VisitScreen(venue, wristband, journey, onClose, kind, onVisitEvent, wristbandConnectionScreen)` | Compose takes lambdas as parameters. |
| `.routeAccessibility(_:)` | `CompositionLocalProvider(LocalRouteAccessibility provides …)` | |
| `VisitEvent` enum cases (`.skipRequested(stop)`) | `VisitEvent` sealed interface (`VisitEvent.SkipRequested(stop)`) | `journey(JourneyEvent)` is `Journey(event)`. The stop is the library's `JourneyStop`. |
| `WristbandPairing` + `WristbandPairingView(pairing:onFinish:)` | `WristbandPairingFlow.of(wristband, scope)` + `WristbandPairingScreen(flow, onFinish)` | |
| `WristbandCopy.message(for:)` returns `String` | `WristbandCopy.message(error)` returns `UiText` | Resolve it with `resolve(context)` or `asString()` in Compose. |
| `UserDefaults` `ProximiioGADKit.accessibleRoutes` | Shared preferences `proximiio_gadkit`, same key | |
| `VisitStore.save` / `load` (static) | `VisitStore.of(context)` | |
| `VenuePlace.all(in: await venue.sdk.features())` | `venue.places(language)` | Also `venue.galleries(language)`. |
| `GeofenceNotifier().run(sdk:)` + `requestAuthorization()` | `GeofenceNotifier(context).run(sdk)` + `AskNotificationPermission` | Android posts on the channel `alerts`. |
| `FieldLog.isEnabled = true` + `logLaunch()`, `FieldLogShareButton` | `FieldLog.start(context, secrets)`, `FieldLog.share(context)` | |
| `DebugLaunch.current.run(with:wristband:)`, launch arguments `-journeyPlayback` | `DebugLaunch.read(activity)` + `run(options, venue, places)`, intent extras `gad.journey` | Debuggable host builds only, as `#if DEBUG`. |
| Live Activity (`ProximiioGADLiveActivity`) | Ongoing journey notification | No widget extension. |
| Geofence events sent as they happen (SDK beta.58) | Sent in batches every 60 s (SDK beta.23) | The Android SDK port is pending. |

## Tests

```
./gradlew :proximiio-gadkit:testDebugUnitTest
```

The UI tests run in the Proximi GAD Android app (`app/src/androidTest`), against the sandbox.

## Versions

| Version | Change |
|---|---|
| 0.12.0 | First Android tag, with the behaviour of iOS 0.12.0: the list's gallery headers by `metadata.gallery_id`, else the gallery outline, else the level; one flat journey list. It includes the iOS changes from 0.4.0 to 0.11.0: `VisitEvent`, `connect(code)` and `wristbandConnectionScreen`, the map style cache, `RouteAccessibility` and the accessible routes button, personalised and curated journeys, artwork details in the list, amenity markers at 30 dp, `GadSettings`, the route's next level change on the card and in red on the map, "Change your route?" as a card, no location choice. |
