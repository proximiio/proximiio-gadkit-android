# ProximiioGADKit for Android

ProximiioGADKit is an Android library with the museum map, the journey (map and list), the stop
picker and Manage journey of the Proximi GAD app (Guggenheim Abu Dhabi), and the wristband binding.
It is built on the Proximi.io Android SDK and Map. It is the Android counterpart of
`proximiio/proximiio-gadkit-ios`: an Android tag `X.Y.0` has the behaviour of the iOS tag `X.Y.0`.

The host app uses one entry point, `ProximiMap`:

- it creates one `ProximiMap` per process and starts it;
- it composes `ProximiMapHost(map)` at the root of its UI;
- it opens screens with `open(screen)`;
- it receives every change as a `ProximiMapEvent`.

`ProximiMap` owns the SDK, the wristband session, the stored journey, the notifications and every
screen and sheet of the library. The host app scans the wristband's QR code itself and passes the
code to `connectWristband(code)`. The Proximi GAD Android app uses the library the same way.

| Artifact | Content |
|---|---|
| `io.proximi.gadkit:proximiio-gadkit` | `ProximiMap`, `ProximiMapHost` and their types, `GadConfiguration`, `GadSettings`, `FieldLog`. |

## Requirements

- Android 8.0 or later (`minSdk` 26), `compileSdk` 37. Kotlin 2.2 and Jetpack Compose.
- Built and tested with Android Gradle Plugin 8.13, Gradle 8.14 and JDK 17.
- Read access to this repository. The Proximi.io SDK and Map are on
  `https://maven.eu.proximi.fi/releases/` (no credentials).

## Adding the library

Add this repository to the host app as a git submodule at a tag, for example at `gadkit`:

```
git submodule add https://github.com/proximiio/proximiio-gadkit-android.git gadkit
git -C gadkit checkout 1.0.0
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

- The versions are the git tags of this repository. From 1.0.0 a minor or patch version does not
  break the public API. New `ProximiMapEvent` cases come in minor versions; a `when` with `else`
  keeps compiling. The section "Changelog" lists every change and marks the breaking ones.
- Declarations marked `@ProximiGadAppApi` are for the Proximi GAD app only. They need an opt-in, are
  not covered by the rule above and can change in any version.
- The library depends on SDK `6.0.0-beta.24` and Map `6.0.0-beta.21` and exposes them, with Compose,
  as `api` dependencies. The host app needs no dependency on them. A host app that also declares
  these artifacts must use the same versions.
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
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | The arrival at the museum and a wristband take-over need a fix. The foreground service needs the location type. |
| `POST_NOTIFICATIONS` | The journey notification, the geofence and next-stop notifications. |
| `CAMERA` (`android.hardware.camera` not required) | The QR scanner of the Proximi GAD app's pairing screen. |

The manifest also removes `ACCESS_BACKGROUND_LOCATION`: the foreground service keeps the process
alive, so background location is never requested. The phone scans nothing (relay-only), so no
Bluetooth permission is requested. Keep the same `tools:node="remove"` entry in the host app's
manifest, so that no other library adds the permission back.

The field log has a `FileProvider` with the authority `<applicationId>.gadkit.fieldlog`.

### Permissions at run time

`ProximiMapHost` asks for the permissions; the host app asks for none for the library:

- Location (`ACCESS_FINE_LOCATION` with `ACCESS_COARSE_LOCATION`): once per process, when
  `ProximiMapHost` is first composed, unless it is allowed. `start()` does not wait for the answer;
  the SDK starts its positioning service once location is allowed. A change in Settings applies when
  the host activity resumes.
- Location for a wristband take-over: before `connectWristband(code)` sends the bind, when the
  relay-api needs a fix.
- Notifications (Android 13 and later): once a wristband is connected, with
  `GadSettings.postsGeofenceNotifications` on. The journey screen also asks when it opens.

### Languages

The library ships English and Arabic strings. Declare both languages in the host app's
`localeConfig`, so that the per-app language applies to the library screens too. The screens
support right-to-left layouts. `ProximiMapException.message` and `WristbandStatus.NotConnected.notice`
are in the app's language at the time they are created.

### Fonts

Guggenheim Sans Display is a font resource of the library. Nothing needs registering.

## Quick start

```kotlin
class MuseumApplication : Application() {
    // One ProximiMap for the life of the process, for example from the DI container.
    val map: ProximiMap by lazy {
        ProximiMap(
            context = this,
            configuration = GadConfiguration(
                apiBaseUrl = "https://api.example.com",
                applicationToken = applicationToken,
                relayApiBaseUrl = "https://relay-api.example.com",
                relayApiAppToken = relayApiAppToken,
            ),
            scope = MainScope(),
        )
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val map = (application as MuseumApplication).map
        setContent {
            // The library's screens open over HomeScreen.
            ProximiMapHost(map) { HomeScreen(map) }
            LaunchedEffect(map) {
                try {
                    map.start()
                } catch (error: ProximiMapException) {
                    // map.status is Failed: show a retry screen.
                }
            }
            LaunchedEffect(map) {
                map.events.collect { event ->
                    when (event) {
                        is ProximiMapEvent.ArtworkReached -> Log.i("Museum", "At ${event.stop.artworkId ?: event.stop.title}")
                        ProximiMapEvent.CraftNewJourneyRequested -> Log.i("Museum", "Show the audio guide")
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(map: ProximiMap) {
    Column {
        Button(onClick = { map.open(ProximiMap.Screen.MUSEUM_MAP) }) { Text("Museum map") }
        Button(onClick = {
            try {
                map.setJourney(JourneyPlan(title = "Light & Perception", artworks = listOf("GAD.2010.00033", "GAD.2018.00034")))
                map.open(ProximiMap.Screen.JOURNEY_READY)
            } catch (error: ProximiMapException) {
                Log.w("Museum", error.message.orEmpty())
            }
        }) { Text("Start the guide's journey") }
    }
}
```

## ProximiMap

`ProximiMap`, `ProximiMapHost` and their types are in the package `io.proximi.gadkit`; `JourneyKind`
and `VisitMode` are in `io.proximi.gadkit.visit`, `RouteAccessibility` in `io.proximi.gadkit.map`,
`TrackingHealth` in `io.proximi.gadkit.wristband`. Call the functions of `ProximiMap` on the main
thread. Its state is in `StateFlow`s, updated on the main thread; in Compose, read them with
`collectAsState()`.

| Member | |
|---|---|
| `ProximiMap(context, configuration, scope)` | Creates the wristband session. Starts nothing. `scope` runs the work for the life of the object: the wristband session, the geofence events and notifications. Create one per process, with a process-wide scope. |
| `suspend fun start()` | Starts the SDK, the wristband session and the notifications, and loads the venue. A second call while starting waits for the first; after a failure it starts again. Throws `ProximiMapException.StartFailed`. |
| `status` | `Status.Idle`, `Starting`, `Ready` or `Failed(error)`. For a splash or a retry screen. |
| `suspend fun connectWristband(code)` | See "Wristband". |
| `suspend fun endVisit()` | Ends the wristband session on the relay-api, for example when the visitor returns the wristband. Throws `ProximiMapException.Wristband` when the relay-api cannot be reached; the session is kept. |
| `wristband` | `WristbandStatus`: `NotConnected(notice)`, `Connecting`, `Connected`. |
| `setJourney(plan): JourneySummary` | Stores a journey. See "Journeys". |
| `deleteJourney()` | Deletes the stored journey. |
| `journey` | `JourneySummary?` of the stored journey: `title`, `summary`, `kind`, `stopCount`, `remainingStopCount`, `isStarted`. It survives app launches. |
| `open(screen)` | Shows a screen. See "Screens". |
| `close()` | Closes the screen or sheet on screen. |
| `screen` | The screen or sheet on screen, or `null`. |
| `events` | `Flow<ProximiMapEvent>`. Each collector receives the events sent while it collects; one that falls more than 100 events behind loses the oldest. |
| `isAtMuseum` | `true` after a wristband or playback position, or a phone fix within 400 m of a place of the venue. |
| `isLocationDenied` | `true` when the visitor answered the location dialog without allowing location, or turned location off in Settings. The host app can show its own screen for it. |
| `routeAccessibility` | `MutableStateFlow<RouteAccessibility>`: the visitor's accessibility needs for routes. See "Accessibility". |
| `configuration` | The `GadConfiguration` it was created with. |

`start()` creates the SDK (`ProximiioConfiguration.relayOnly`, no fallback host), authenticates,
starts positioning, loads the route network and attaches the wristband's position provider. Visitor
reporting is on. Call it at launch: the map style and the venue data are then loaded before the first
map screen. On the first map screen after an install, MapLibre downloads the basemap tiles while the
map is on screen; until they arrive, the map shows the style's white background. The library
contacts only `apiBaseUrl` and the relay-api host.

`GadConfiguration` throws `IllegalArgumentException` when `relayApiBaseUrl` has no host.
`toString()` leaves the tokens out; `secrets` lists them for `FieldLog.start`.

## Screens

`ProximiMapHost(map, modifier, content)` shows the screen `open(screen)` asks for over `content`, the
host app's UI. Compose it once, at the root. Nothing opens before `start()` has finished.

| `ProximiMap.Screen` | Shows | Inside the flow |
|---|---|---|
| `MUSEUM_MAP` | The museum map: floors, amenities, the visitor's position, search, a route to a tapped place, the gallery legend. | Back closes it. |
| `JOURNEY_READY` | Journey ready (02.10A): the journey's title and summary. Needs a stored journey. | "Start the Journey" (at the museum) opens the journey. "Go to Museum" (away from it) and "Back to Hub" close it. |
| `JOURNEY` | The journey: map and list, the stop card, guidance from stop to stop, detours to amenities, the journey notification, Journey Complete. Needs a stored journey. | Back on a journey in progress (a stop visited or skipped, not finished) asks "Leave the tour here" first; otherwise it closes. |
| `PLAN_JOURNEY` | The stop picker ("Plan your visit"). | "Start" stores a personalised journey and opens Journey ready. |
| `MANAGE_JOURNEY` | Manage journey (02.11). Needs a stored journey. | "Edit stops" opens the stop picker, or sends `CuratedTourChangeRequested` for a curated tour. "Craft a new journey" sends `CraftNewJourneyRequested`. "Delete journey", then "Delete this journey", deletes it. |

- The museum map, Journey ready, the journey and the stop picker fill `ProximiMapHost`. Manage
  journey is a bottom sheet.
- One screen at a time. A screen on screen closes first; the next one shows once it has gone, or
  after 0.7 s.
- `ProximiMapEvent.Closed` follows when a screen has gone.
- Under a full-screen screen, `content` gets no touches and is not in the accessibility tree.
- The screen on screen and the journey's state stay when the activity is recreated, for example
  after a language change. After the process ends, the stored journey stays; no screen is open.
- The journey screen saves its progress on every change. Journey ready and the journey read the
  stored journey when they open.
- In an app with Fragments, compose `ProximiMapHost(map)` in a `ComposeView` that fills the
  activity's window above the fragment container, without `content`. It draws nothing and passes
  touches through while no screen of the library is shown.

## Journeys

```kotlin
val summary = map.setJourney(
    JourneyPlan(
        title = "Light & Perception",
        summary = "6 artworks · 45 min",          // null shows "6 stops"
        kind = JourneyKind.PERSONALISED,
        artworks = listOf("GAD.2010.00033", "GAD.2018.00034"),
    ),
)
map.open(ProximiMap.Screen.JOURNEY_READY)
```

- `artworks` are in visit order. Each one is an artwork's collection id (`remote_id`) or a Proximi.io
  POI id. Ids that match no POI are left out; `summary.stopCount` is the number kept.
- `setJourney` throws `ProximiMapException.NotStarted` before `start()` has finished, and
  `ProximiMapException.NoKnownArtworks` when no id matches.
- A new journey replaces the stored one. `ProximiMapEvent.JourneyChanged` follows every change.

A journey the visitor built with the guide is `JourneyKind.PERSONALISED`; a tour by the museum's
curators is `JourneyKind.CURATED` (Figma "Personalised" and "Guided"):

| | `PERSONALISED` | `CURATED` |
|---|---|---|
| Stop card | Names the gallery of the next stop until the visitor is in it, then the artwork. | Names the artwork. |
| List | Stops under gallery headers. | Stops without headers. |
| Skip | The visitor can skip an artwork, also one no accessible route reaches. | No skip. |
| Order | Ordered floor by floor and gallery by gallery from the visitor, at the start and after a detour or an artwork visited out of order. | The curators' order. An artwork visited out of order is marked visited; the rest keep their order. |

The map and the background are the same for both.

## Wristband

The host app scans the wristband's QR code, or the visitor types the code printed on it:

```kotlin
scope.launch {
    try {
        map.connectWristband(scannedCode)
    } catch (error: ProximiMapException) {
        errorText = error.message   // the text for the visitor, English or Arabic
    }
}
```

- `connectWristband(code)` trims the code and sends it to the relay-api, which resolves every label
  format. When the relay-api needs the phone's location for a take-over, the system location dialog
  comes first (`ProximiMapHost` shows it). It returns when the session is active. It throws
  `ProximiMapException.Wristband` with the text for the visitor, for example "This wristband is
  already in use. Please ask the staff."
- A wristband that another phone follows is taken over when the relay-api allows it. The other
  phone's session ends; its `wristband` becomes `NotConnected(notice)` with "Your wristband was
  scanned by another phone. Connect it again to follow it on this phone."
- Positions arrive only while `wristband` is `Connected`. A session stored by the SDK is confirmed
  with the relay-api after `start()`.
- The library shows no wristband alerts. `ProximiMapEvent.WristbandChanged` and `TrackingChanged`
  let the host app show its own.

## Events

`ProximiMap` performs every action itself; the events report them. They are sent on the main thread.

| Event | When |
|---|---|
| `Opened(screen)`, `Closed(screen)` | A screen opened or closed, by `open(screen)` or inside a flow. |
| `CraftNewJourneyRequested` | "Craft a new journey" on Manage journey, after it closed. The host app shows its journey crafting. |
| `CuratedTourChangeRequested` | "Edit stops" on Manage journey for a curated tour, after it closed. The host app shows its tour choice. |
| `ArrivedAtMuseum` | `isAtMuseum` became `true`. Once per `ProximiMap`. |
| `WristbandChanged(status)` | The wristband session changed. |
| `TrackingChanged(health)` | During a journey: the wristband's positions stopped (`LOST_PLACE` after `trackingLostAfter`, `STOPPED_RESPONDING` after `trackingStoppedAfter`) or came back (`TRACKING`). |
| `GeofenceEntered(geofence)`, `GeofenceExited(geofence)` | Every geofence enter and exit, with or without a journey. An artwork geofence has `type` `artwork` and the collection id in `artworkId`. |
| `JourneyChanged(journey)` | A new, planned, edited or deleted (`null`) journey, and the progress after the journey screen closed. |
| `NextStop(stop)` | The route leads to this stop now. |
| `StopNearby(stop)` | The active stop is less than `stopNearbyMeters` away on the visitor's level. Once per stop. |
| `ArtworkReached(stop)` | `artworkAdvanceDelay` after the visitor entered the artwork geofence of the active stop, or stood in its area: the stop is done and the route leads to the next stop. `GeofenceEntered` arrives at the enter. |
| `ArtworkVisitedOutOfOrder(stop)` | The visitor stayed at an artwork further down the journey. It counts as visited. In a personalised journey the stops ahead are ordered again; a curated tour keeps its order. |
| `SkipRequested`, `StopSkipped`, `SkipCancelled`, `SkipUndone` (`stop`) | A swipe on a stop in the list (or its "Skip" TalkBack action), "Skip artwork", "Keep it", "Undo skip". |
| `AmenitySelected(stop)` | An amenity picked on the map. A card shows it with "Go here". |
| `DetourStarted(stop)`, `DetourEnded(stop, completed)` | "Change your route?" confirmed; the detour reached (`completed`) or left. |
| `JourneyResumed` | "Resume your Journey" or "Show the route from here". |
| `OffRoute(duration)`, `BackOnRoute` | The visitor has been off the route for `offRouteAfter`; the visitor is on the route again. |
| `JourneyFinished` | Every stop is visited or skipped. Journey Complete shows. |
| `FloorWithoutPositioningEntered(level)`, `FloorWithoutPositioningLeft(level)` | See "Floors without positioning". |
| `ModeChanged(mode)`, `Recentred`, `AccessibleRoutesChanged(isOn)` | The Map / List toggle (`VisitMode`), "Centre on me", the accessible routes button. |
| `ExitRequested`, `ExitConfirmed`, `ExitCancelled` | Back during a journey in progress (a stop visited or skipped, not finished), "Leave the tour", "Carry on". |

The museum map sends only `Opened` and `Closed`; the other screen events come from the journey
screen.

`ProximiStop` has the POI `id`, the `title`, the artwork's collection id `artworkId` (`null` for an
amenity) and the `level`. `ProximiGeofence` has the geofence `id`, `name`, `type` and `artworkId`.

## Settings

Every time and distance of the screens is in `GadSettings`. Set them in the configuration. Pass the
arguments by name:

```kotlin
val configuration = GadConfiguration(
    apiBaseUrl = apiBaseUrl,
    applicationToken = applicationToken,
    relayApiBaseUrl = relayApiBaseUrl,
    relayApiAppToken = relayApiAppToken,
    settings = GadSettings(offRouteMeters = 50.0, postsGeofenceNotifications = false),
)
```

| Setting | Default | Effect |
|---|---|---|
| `trackingLostAfter` | 110 s | Time without positioning before `ProximiMapEvent.TrackingChanged(LOST_PLACE)`. The SDK reports positioning unavailable about 10 s after the last fix: about 2 min. |
| `trackingStoppedAfter` | 170 s | Time without positioning before `TrackingChanged(STOPPED_RESPONDING)` (about 3 min). |
| `artworkAdvanceDelay` | 2 s | From the arrival at an artwork (its geofence) to the route to the next stop. |
| `outOfOrderDwell` | 0 s | Time inside another artwork's geofence before it counts as visited out of order. |
| `amenityArrivalMinimumMeters`, `amenityArrivalMaximumMeters`, `amenityArrivalDwell` | 5 m, 10 m, 5 s | An amenity is reached within 2.5 × the position accuracy, within these bounds, for this time. |
| `stopNearbyMeters` | 5 m | The "next stop is less than … m away" notification. |
| `offRouteMeters`, `offRouteAfter` | 30 m, 300 s | "You've left your route": this far from the route, or off it this long. |
| `routeSnapMeters` | 0.5 m | The dot is drawn on the route within this distance. Display only. |
| `followZoom` | 21 | Zoom of "Centre on me" and of the first position. |
| `liftRideDwell`, `liftExitFixes` | 3 s, 2 | Time of fixes inside a lift outline before the visitor counts as riding it, and the fixes outside that end the ride. The field log records the rides. |
| `levelsWithoutPositioning` | [0] | Floors without positioning. See "Floors without positioning". |
| `levelChangerReachedMeters` | 5 m | Distance from the lift or stairs to a floor without positioning at which the lift or stairs view shows. |
| `levelChangeViewDuration` | 5 s | How long the lift or stairs view shows before the map shows the floor without positioning. |
| `floorWithoutPositioningMaximum` | 1800 s | After this time on a floor without positioning with the same destination, the map follows the visitor again. |
| `uncertainAccuracyMeters`, `locationQuestionQuietPeriod` | 8 m, 120 s | "Probably at …": an uncertain position near the next stop, and how long an answered prompt stays quiet. |
| `firstFixWait`, `lateOrderWindow` | 5 s, 60 s | A new personalised journey waits this long for a fix before it is ordered without one, and the first fix within the window orders it again. |
| `journeyNotificationMinimumInterval` | 5 s | The shortest gap between two updates of the instruction in the journey notification. |
| `postsGeofenceNotifications` | `true` | A notification for every enter and exit of a geofence with a name ("You entered this area.", "You left this area."), and the notification permission request once a wristband is connected. The "next stop" notification of the journey (`stopNearbyMeters`) does not depend on it. |

Times are `kotlin.time.Duration`, distances metres.

The geofence and "next stop" notifications use the channel `alerts` (high importance, no sound). A
notification replaces the earlier one of the same geofence and direction; privacy zones post
nothing. The journey notification uses the channel `visit`; the SDK's positioning service has its
own channel. Without the notification permission nothing is shown.

## The journey list

A row shows the artist and "AR" and audio badges from the artwork's POI `properties.metadata`.
Without `artist_name` the row shows the level.

| Key | Value | Shows |
|---|---|---|
| `artist_name` | String, or an i18n object like `title_i18n` | The artist under the title. |
| `has_ar` | `true`, `"true"` or a non-zero number | "AR". |
| `has_audio` | `true`, `"true"` or a non-zero number | A note. |
| `gallery_id` | The `remote_id` of a gallery POI | The stop's gallery header. |

The header of a stop is its gallery by `gallery_id`, named by the title of the POI whose
`remote_id` is that id ("G403 Weaving"); else the gallery outline it lies in; else its level
("Level 4"). The GAD venue data on 2026-10-15 has `gallery_id` on artworks in 18 galleries; 14 of
the 18 have a gallery POI.

## Floors without positioning

A floor in `GadSettings.levelsWithoutPositioning` has no positioning. In stage 1 that is the GAD
level 0, with the café and the prayer room. A route there, on the journey map and the museum map:

1. leads to the lift or stairs as usual;
2. within `levelChangerReachedMeters` of the lift or stairs, shows the lift or stairs view ("Take the
   stairs to level 0", "Your position is not shown on this level.") for `levelChangeViewDuration`; a
   tap ends it;
3. shows that floor with the rest of the route. The map does not follow the visitor there, no
   off-route warning and no "Probably at …" shows, and the card says that the position is not shown.

The map follows the visitor again when the destination changes ("Resume your Journey", another stop
or route), on "Centre on me" or "Follow my floor", or after `floorWithoutPositioningMaximum`. The
journey screen sends `FloorWithoutPositioningEntered(level)` and `FloorWithoutPositioningLeft(level)`.
The view shows the lift or stairs icon until the animation from the design team exists. The view
has the test tag `floorChangeView`.

## Accessibility

The host app sets the visitor's accessibility needs. Every route then avoids what they name, and a
change re-routes the route on screen:

```kotlin
map.routeAccessibility.value = RouteAccessibility(avoidsStairs = true, avoidsRevolvingDoors = true)
```

| Need | Avoids, where the venue data marks it |
|---|---|
| `avoidsStairs` | Level changers with `type` `staircase`. |
| `avoidsEscalators` | Level changers with `type` `escalator`. |
| `avoidsRevolvingDoors` | Path pieces within `radius` of a `door` point feature. |
| `avoidsInaccessiblePaths` | Routing paths with `narrowPath: true`. |

- The GAD venue data on 2026-10-14 has staircases and elevators. It has no escalators, no `door`
  features and no `narrowPath` paths, so only `avoidsStairs` changes routes until the data has them.
- The museum map and the journey map have an accessible routes button (a wheelchair, blue while on).
  While it is on, routes avoid all four (`RouteAccessibility.ACCESSIBLE`), whatever the host app set.
  Its state is kept on the device for both maps and later launches.
- When no route meets the needs, the museum map shows "No route from here with your accessibility
  settings." In a journey, the stop card shows "No route to this stop with your accessibility
  settings." and the stop keeps its place; in a personalised journey the visitor can skip it in the
  list.

## Journey notification

The iOS Live Activity is an ongoing notification on Android: the current step, "Stop N of M", the
next instruction and the level. The journey screen posts, updates and removes it (channel `visit`).
It needs the notification permission.

## Field log

`FieldLog` writes positions, tracking health, geofence, journey, binding and `ProximiMap` events to
`files/fieldlog/gad-debug.log` in app-private storage, and to Logcat with the tag `GAD`. It is off by
default. Turn it on at launch, before `start()`, in test builds only:

```kotlin
FieldLog.start(context, configuration.secrets)   // the tokens are replaced by <redacted>
Proximiio.logSink = { entry -> FieldLog.log("sdk ${entry.category} ${entry.level.label.lowercase()}: ${entry.message}") }
```

`FieldLog.share(context)` opens the share sheet with the file. The file is cut to its last 4 MB when
it passes 8 MB.

## Debug builds

In a debuggable host build, `start()` reads these intent extras of the activity that composes
`ProximiMapHost`:

```
adb shell am start -n <applicationId>/<activity> --es gad.journey <journey id> --es gad.journeySpeed 2 --ez gad.journeyLoops true
```

| Extra | Effect |
|---|---|
| `gad.journey <journey id>` (alias `gad.journeyPlayback`) | Plays a journey stored on Proximi.io instead of the wristband. |
| `gad.journeySpeed <x>` | Playback speed. Default 1. |
| `gad.journeyLoops true` | Restarts playback after the last waypoint. |
| `gad.listJourneys true` | Logs the organisation's places and journeys with their ids. |
| `gad.pairWristband <code>` | Binds the wristband after the start. Ignored with `gad.journey`. |
| `gad.visit "<title>,<title>,…"` | Stores a journey through the first place matching each title and opens it. |
| `gad.visitKind personalised\|curated` | With `gad.visit`: the kind of journey. Default personalised. |
| `gad.visitList true` | The journey opens in List mode. |
| `gad.visitAutoAdvance true` | A reached detour ends 5 m after the visitor leaves it, without "Resume your Journey". |
| `gad.openMap true` | Opens the museum map. |
| `gad.routeTo <title>` | The museum map routes to the first place whose title matches, after the first position. |
| `gad.mapGalleryFills true` | The museum map fills every gallery. |
| `gad.routeAvoid stairs,escalators,revolvingDoors,inaccessiblePaths` | Sets `routeAccessibility`. |

`gad.hubPage` is read by the Proximi GAD app; the library does not act on it.

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
  5 m), and geofence enter and exit events. Positions are uploaded every 60 s and when the app goes
  to the background. Each geofence enter or exit is sent at once. Without network they wait on the
  device and go out once the network is back. Beacon and privacy-zone events are not uploaded. The
  client requires no consent prompt for it.
- The Play Console Data safety form of the host app declares precise location and a device
  identifier (the anonymous visitor id), sent to Proximi.io, not shared and not used for tracking.
- `ProximiMapHost` asks for location while the app is in use and for notifications. The library
  never asks for background location.

## Differences from the iOS package

| iOS | Android | Note |
|---|---|---|
| `ProximiMap(configuration:)` | `ProximiMap(context, configuration, scope)` | Android needs a context. `scope` runs the session, the event streams and the notifications. Create one per process, for example in the `Application`. |
| `@Observable` properties | `StateFlow`s; `routeAccessibility` is a `MutableStateFlow` | Read them in Compose with `collectAsState()`. |
| `events`: a new `AsyncStream` per access, the newest 100 buffered | `events`: one `Flow` for all collectors, 100 buffered per collector, the oldest dropped | |
| `.proximiMap(_:)` on a view | `ProximiMapHost(map) { content }` at the root | Compose takes the content as a parameter. With Fragments, a `ComposeView` above the fragment container. |
| Full-screen covers and sheets | Screens in `ProximiMapHost`, in the activity's window; Manage journey is a bottom sheet | The stop picker is full screen (iOS: a sheet). `open` can be called while a screen of the host app is closing. |
| `start()` asks for location and waits for the answer | `ProximiMapHost` asks once per process when it is first composed; `start()` does not wait | A permission dialog needs an activity. The SDK starts its positioning service once location is allowed; a change in Settings applies on resume. |
| `isLocationDenied` from the authorization status | `true` only after the dialog of `ProximiMapHost` was answered | Android does not tell a refusal from a permission never asked for. |
| Notification authorization asked once a wristband is connected | `ProximiMapHost` asks for `POST_NOTIFICATIONS` (Android 13 and later) | The geofence notifications use the channel `alerts`. |
| The location dialog of a take-over | `ProximiMapHost` shows it | |
| `ProximiMapError`, `localizedDescription` | `ProximiMapException` subclasses, `message` | `startFailed(String)` is `StartFailed` with `reason`. |
| `ProximiMap.Screen.museumMap` … | `ProximiMap.Screen.MUSEUM_MAP` … | |
| `ProximiMapEvent` enum cases (`.artworkReached(stop)`) | `ProximiMapEvent` sealed interface (`ProximiMapEvent.ArtworkReached(stop)`) | `offRoute(duration: TimeInterval)` is `OffRoute(duration: Duration)`. `artworkID` is `artworkId`. |
| `TrackingHealth` cases (`.lostPlace`), `JourneyKind` (`.curated`), `VisitMode` | Enums (`LOST_PLACE`, `CURATED`, `MAP`) | |
| `GADConfiguration(apiBaseURL: URL, …, relayAPIBaseURL: URL, …)`, `nil` for an invalid relay-api URL | `GadConfiguration(apiBaseUrl: String, …, relayApiBaseUrl: String, …)` | An invalid relay-api URL throws `IllegalArgumentException`. |
| `GADSettings` (`var`s, seconds as `TimeInterval`) | `GadSettings` (data class, `Duration`) | `offRouteSeconds` is `offRouteAfter`; `levelChangeViewSeconds` is `levelChangeViewDuration`; `floorWithoutPositioningMaximumSeconds` is `floorWithoutPositioningMaximum`; `liveActivityMinimumInterval` is `journeyNotificationMinimumInterval`. |
| `@_spi(GADApp)` | `@ProximiGadAppApi` (`RequiresOptIn`, level `ERROR`) | The Android pairing screen is `WristbandPairingScreen(map.makeWristbandPairing(), onFinish)`. Android has no `DemoPairing`. |
| Launch arguments in the Debug configuration (`-journeyPlayback`) | Intent extras in a debuggable build (`gad.journey`) | Read from the activity that composes `ProximiMapHost`. |
| A view keeps its state while SwiftUI redraws it | A recreated activity keeps the screen on screen and the journey's state | For example after a language change. After the process ends only the stored journey stays. |
| Live Activity (`ProximiioGADLiveActivity`) | Ongoing journey notification | No widget extension. |
| `FieldLog.isEnabled = true` + `logLaunch()`, `FieldLogShareButton` | `FieldLog.start(context, secrets)`, `FieldLog.share(context)` | |
| 0.15.0: SDK 6.0.0-beta.59 and Map 6.0.0-beta.44, text for the swap and signing errors | Not in 1.0.0 | It waits for the Android SDK 6.0.0-beta.25 and a Map release. |

## Tests

```
./gradlew :proximiio-gadkit:testDebugUnitTest
```

The JVM tests cover the rules without a map or an SDK: `ProximiMap` (artwork ids, the journey
summary, the wristband status, the events, the screen order), the card and instruction text, the
ride merge, the floor without positioning, galleries, level changers, artwork geofences, journey
order and the settings. The UI tests run in the Proximi GAD Android app (`app/src/androidTest`),
against the sandbox.

## Changelog

The format follows [Keep a Changelog 1.1](https://keepachangelog.com/en/1.1.0/). Every change of
the library adds an entry under `[Unreleased]` in the same commit. A release moves the entries
under a heading with the version and the date. A tag `X.Y.0` has the behaviour of the iOS tag
`X.Y.0`. Entries before 1.0.0 name the types of the API before `ProximiMap`.

- **BREAKING:** the host app has to change its code: a public API is renamed or removed, a
  parameter is new and required, a type changes, or a default changes. The entry says what to change.
- **Visible:** the screens or the behaviour change and the API stays the same. The entry says
  whether the host app has to do something.

### [Unreleased]

Version 1.0.0, with the behaviour of the iOS package 1.0.1 without 0.15.0 (see "Differences from
the iOS package"). One entry point, `ProximiMap`, replaces the screens and services of 0.14.0
(Nagarro, 2026-10-16: "a single exposed wrapper component that encapsulates all internal UI
components and logic"). From 1.0.0 a minor or patch version does not break the public API.

#### Changed

- **BREAKING:** these are no longer public; `ProximiMap` and `ProximiMapHost` do what they did:

  | 0.14.0 | 1.0.0 |
  |---|---|
  | `WristbandSession(context, configuration, scope)` + `VenueService.start(context, configuration, wristband.binding)` | `ProximiMap(context, configuration, scope)` + `start()` |
  | `venue.sdk` | `ProximiMapEvent` (for example `GeofenceEntered`, `ArtworkReached`). The host app does not use the SDK instance. |
  | `WristbandSession.connect(code)`, `end()`, `state`, `isActive`, `endNotice`, `WristbandCopy.message(error)`, `BindingError` | `connectWristband(code)`, `endVisit()`, `wristband`, `ProximiMapException.Wristband` and its `message` |
  | `WristbandLocationDialogHost(wristband)`, the location request before `VenueService.start`, `AskNotificationPermission(isNeeded)` | `ProximiMapHost` asks. |
  | `MuseumMapScreen`, `VisitScreen`, `JourneyReadyScreen`, `VisitPlanPicker`, `ManageJourneySheet` | `ProximiMapHost(map)` and `open(Screen.MUSEUM_MAP)`, `JOURNEY`, `JOURNEY_READY`, `PLAN_JOURNEY`, `MANAGE_JOURNEY` |
  | `VisitPlan.journey(poiIds, places, galleries)`, `VisitStore`, `VisitMeta`, `venue.places(language)`, `venue.galleries(language)` | `setJourney(JourneyPlan)`, `deleteJourney()`, `journey` |
  | `VisitScreen(onVisitEvent = { VisitEvent })` | `map.events.collect { ProximiMapEvent }` |
  | `CompositionLocalProvider(LocalRouteAccessibility provides …)` | `map.routeAccessibility.value = …` |
  | `GeofenceNotifier(context).run(venue.sdk)` | `GadSettings.postsGeofenceNotifications` (default `true`) |
  | `DebugLaunch.read(activity)` + `DebugLaunch.run(options, venue, places)` | `start()` runs the debug extras in debuggable builds. |
  | `VenueService.settings` | `GadConfiguration.settings` |
  | `VenuePlace`, `Gallery`, `Journey`, `JourneyStop`, `StopState`, `StopKind`, `Coordinate`, `UiText`, `BindingState`, `JourneyKind.of` and the `JourneyKind` rules, `RouteAccessibility.routeOptions`, `PREFERENCES_FILE`, `ACCESSIBLE_ROUTES_KEY` | Internal. |
  | `WristbandPairingFlow.of(wristband, scope)`, `WristbandPairingScreen`, the design system (`Palette`, `GadTextStyle`, `GuggenheimSansDisplay`, the buttons, `glassSurface`, the glass backdrop, `AuroraBox`, `LabeledField`), `BackButton`, `OverlaySheet`, `asHeading`, `DebugLaunch` | Behind `@ProximiGadAppApi`: not for host apps. |

- **BREAKING:** `ProximiMapEvent` has library payloads (`ProximiStop`, `ProximiGeofence`) instead of
  the library's `JourneyStop` and `VenuePlace` and the map library's `JourneyEvent`.
- **BREAKING:** the library depends on the Proximi.io Android SDK `6.0.0-beta.24` (was
  `6.0.0-beta.23`); the Map stays `6.0.0-beta.21`. A host app that declares the SDK artifacts
  itself moves them to `6.0.0-beta.24`. The SDK's `ProximiioConfiguration` has a new parameter
  `eventBatching` before `positioning`: a host app that builds a `ProximiioConfiguration` passes
  `positioning` and the parameters after it by name, and recompiles.
- **Visible:** each geofence enter and exit is sent to `apiBaseUrl` at once, not in the batch every
  60 s. Beacon and privacy-zone events are no longer uploaded; the SDK still delivers them to the
  app. See "Privacy". No change in the host app.
- **Visible:** `ProximiMapHost` asks for location once per process when it is first composed, and
  for notifications once a wristband is connected. Remove the host app's own requests for the
  library.

#### Added

- `ProximiMap`: `start()`, `status`, `connectWristband(code)`, `endVisit()`, `wristband`,
  `setJourney(plan)`, `deleteJourney()`, `journey`, `open(screen)`, `close()`, `screen`, `events`,
  `isAtMuseum`, `isLocationDenied`, `routeAccessibility`.
- `ProximiMapHost(map, modifier, content)` presents the screens and sheets; one at a time, the next
  after the previous one has gone.
- Journeys by artwork collection id (`remote_id`) or POI id (`JourneyPlan.artworks`).
- `ProximiMapEvent.GeofenceEntered` and `GeofenceExited` for every geofence, with or without a
  journey.
- `NextStop`, `DetourEnded`, `OffRoute`, `BackOnRoute` and `JourneyFinished` events from the journey
  navigator.
- Manage journey sends `CraftNewJourneyRequested` and, for a curated tour,
  `CuratedTourChangeRequested`.
- `GadSettings.postsGeofenceNotifications` (default `true`), after
  `journeyNotificationMinimumInterval`.
- `isAtMuseum` and `ArrivedAtMuseum`, moved from the Proximi GAD app. A phone fix counts within
  400 m of any place of the venue. The app compared it with the mean of all places, which for the
  two GAD venues 11 km apart is near neither.
- Debug extras `gad.openMap` and `gad.pairWristband`, as the iOS launch arguments `-openMap` and
  `-pairWristband`. `gad.visit` stores the journey before it opens it.

#### Fixed

- **Visible:** the position dot no longer stops during the visitor-analytics upload, which ran
  every 60 s for up to the request timeout. No change in the host app.

### [0.14.0] — 2026-10-15

The behaviour of the iOS package 0.14.0.

#### Removed

- **BREAKING:** `VisitScreen` has no `wristband` and no `wristbandConnectionScreen` parameter. Remove
  both arguments: `VisitScreen(venue, journey, onClose, kind, onVisitEvent)`. The screen no longer
  offers "Connect a wristband"; the host app handles an ended wristband session itself
  (`WristbandSession.state`, `endNotice`).
- **BREAKING:** `VenuePlace.welcomeDesk(places)` is removed. It served the route to the Welcome Desk
  of the tracking alert. Remove the call. A host app that needs the desk finds it in
  `venue.places(language)` by its title or `amenityId`.
- **Visible:** the tracking alerts "We've lost your place in the room" and "Your wristband has
  stopped responding", and the "Your wristband is no longer connected" sheet with "Connect a
  wristband" and the route to the Welcome Desk. The host app handles the wristband states
  (PM 2026-10-15). Use `VisitEvent.TrackingChanged` to show your own.
- **Visible:** the level change animation over the map ("Level up", "In the lift"). The map still
  moves to the visitor's floor. No change in the host app.

#### Added

- **Visible:** floors without positioning (see the section): at the lift or stairs to such a floor
  the lift or stairs view shows, then the map of that floor with the rest of the route, on the
  journey map and the museum map. In stage 1 that is GAD level 0. No change in the host app; set
  `GadSettings.levelsWithoutPositioning` to change the floors.
- **BREAKING:** `GadSettings.levelsWithoutPositioning` ([0]), `levelChangerReachedMeters` (5 m),
  `levelChangeViewDuration` (5 s) and `floorWithoutPositioningMaximum` (1800 s). The four parameters
  follow `liftExitFixes`, so a positional call of the constructor gets other values. Pass the
  arguments of `GadSettings` by name.
- **BREAKING:** `VisitEvent.FloorWithoutPositioningEntered(level)` and
  `FloorWithoutPositioningLeft(level)`. A `when` over `VisitEvent` without `else` needs the cases.
- **BREAKING:** `VisitEvent.TrackingChanged(health)` with the public enum `TrackingHealth`
  (`WAITING`, `TRACKING`, `LOST_PLACE` after `GadSettings.trackingLostAfter`, `STOPPED_RESPONDING`
  after `trackingStoppedAfter`). A `when` over `VisitEvent` without `else` needs the case.
- `SmallButton(…, strength = …)`: the glass of the button, `GlassStrength.Light` by default.

#### Changed

- **Visible:** the gallery headers of the journey list show the title of the POI whose `remote_id`
  is the artwork's `metadata.gallery_id` ("G403 Weaving"), not the name of the gallery geofence.
  No change in the host app.
- **Visible:** the stop card, the instruction and the journey notification name the floor where a
  ride in one staircase or lift ends ("Take the stairs to level 4"). The SDK router splits a ride
  into one level change per floor, and the card named the first one ("level 3"). No change in the
  host app.
- **Visible:** lifts, staircases and escalators show on their floor and on every floor in their
  `levels`, on the journey map and the museum map. The route's next one stays red. No change in the
  host app.
- **Visible:** the amenity buttons of the map search use the frosted glass, so their titles are
  readable over the map. No change in the host app.

#### Fixed

- **Visible:** the map's amenity markers (restrooms, café, help desk, prayer room, shop, stairs,
  elevator) are white circles with a dark icon, as in the iOS package. In 0.12.0 the circles were
  black, and the icons were hard to see. No change in the host app.
- **Visible:** an amenity button picks the nearest place in the venue (`place_id`) of the nearest
  one, then prefers the visitor's level. From level 1 of the Guggenheim the café button chose the
  DCT office café, on level 1 but 11 km away, over the Guggenheim café on level 0. No change in the
  host app.
- **Visible:** an artwork with several geofences of the same `remote_id` is reached in any of them.
  Each DCT test artwork shares its `remote_id` with the Guggenheim artwork, and only the first
  geofence counted, so a visitor in the DCT geofence did not reach "DCT Mother/Earth", at the start
  of a journey or out of order. No change in the host app.

### [0.12.0] — 2026-10-15

First release, with the behaviour of the iOS package 0.12.0. The section "Differences from the iOS
package" lists what Android does differently.

#### Added

- `GadConfiguration`, `GadSettings` and `VenueService.start`: the SDK start, the map style cache,
  and every time and distance of the screens.
- `WristbandSession` with `connect(code)` for a host app with its own QR scanner, and
  `VisitScreen(wristbandConnectionScreen = …)` for the host's connection screen.
- `MuseumMapScreen` and `VisitScreen` (the journey map and list), and `VisitEvent` for analytics.
- `JourneyKind`: personalised journeys and curated tours. A curated tour keeps the curators' order.
- `RouteAccessibility` and the accessible routes button on both maps.
- The journey list: gallery headers by `metadata.gallery_id`, else by the gallery outline, else by
  the level; one flat list; artist and AR and audio badges.
- Amenity markers at 30 dp.
- The card names the route's next level change, and the map marks that lift or stairs in red.
  "Change your route?" is a card on the map.
- `GeofenceNotifier`, the "next stop nearby" notification and the ongoing journey notification.
- `FieldLog` and `DebugLaunch` for debuggable builds.
