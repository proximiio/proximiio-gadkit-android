# ProximiioGADKit for Android

ProximiioGADKit is an Android library with the Explore Museum map, the journey (map and list), the stop
picker and Manage journey of the Proximi GAD app (Guggenheim Abu Dhabi), and the wristband binding.
It is built on the Proximi.io Android SDK and Map. It is the Android counterpart of
`proximiio/proximiio-gadkit-ios`: an Android tag `X.Y.0` has the behaviour of the iOS tag `X.Y.0`.
One exception: Android 1.15.0 has the behaviour of iOS 1.14.0 and `ProximiExploreView` of iOS 1.15.0.

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
| `io.proximi.gadkit:proximiio-gadkit` | `ProximiMap`, `ProximiMapHost`, `ProximiMapView`, `ProximiExploreView` and their types, `GadConfiguration`, `GadSettings`, `FieldLog`. |

## Requirements

- Android 8.0 or later (`minSdk` 26), `compileSdk` 37. Kotlin 2.2 and Jetpack Compose.
- Built and tested with Android Gradle Plugin 8.13, Gradle 8.14 and JDK 17.
- The library, the Proximi.io SDK and the Map are on `https://maven.eu.proximi.fi/releases/` (no
  credentials).

## Adding the library

In the host app's `settings.gradle.kts`, add the Proximi.io repository with the groups of the
library, the SDK and the Map:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven("https://maven.eu.proximi.fi/releases/") {
            content {
                includeGroup("io.proximi.gadkit")
                includeGroup("io.proximi.sdk")
                includeGroup("io.proximi.map")
            }
        }
        google()
        mavenCentral()
    }
}
```

Then depend on the library at a version:

```kotlin
dependencies {
    implementation("io.proximi.gadkit:proximiio-gadkit:1.22.2")
}
```

The host app can also build the library from its source. Add this repository as a git submodule at
a tag, for example at `gadkit`:

```
git submodule add https://github.com/proximiio/proximiio-gadkit-android.git gadkit
git -C gadkit checkout 1.22.2
```

Add `includeBuild("gadkit")` to `settings.gradle.kts` and depend on the library without a version.
Gradle resolves it to the included build.

- The versions are the git tags of this repository. From 1.0.0 a minor or patch version does not
  break the public API. New `ProximiMapEvent` cases come in minor versions; a `when` with `else`
  keeps compiling. The section "Changelog" lists every change and marks the breaking ones.
- Declarations marked `@ProximiGadAppApi` are for the Proximi GAD app only. They need an opt-in, are
  not covered by the rule above and can change in any version.
- The library depends on SDK `6.0.0-beta.36` and Map `6.0.0-beta.32` and exposes them, with Compose,
  as `api` dependencies. The host app needs no dependency on them. A host app that also declares
  these artifacts must use the same versions.
- The library uses `com.airbnb.android:lottie-compose` for the lift and stairs animations
  (`implementation`). Gradle takes the highest Lottie version the host app and the library request.
- A host app that draws its journey with the map library's `ProximiioMap` and its own
  `JourneyNavigator` moves to `ProximiMapView` with [MIGRATION.md](MIGRATION.md). It lists the SDK
  and Map changes since SDK 6.0.0-beta.23 and Map 6.0.0-beta.21.
- The library ships its own R8 consumer rules. The host app needs no rule for it.
- With the library as an included build and the Gradle configuration cache on, set
  `kotlin.internal.collectFUSMetrics=false` in the host app's `gradle.properties`. Otherwise the
  Kotlin Gradle plugin registers its build statistics service in both builds, and the configuration
  fails ("Cannot set the value of a property of type BuildFusService"). The library from Maven does
  not need it.

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

## ProximiMapView

`ProximiMapView` is the journey map as a UI component on an SDK the host app has started: the Map /
List toggle, search with the amenity shortcuts, the map with the floor button, "Centre on me", the
accessible routes button and the stop card, and the list. The host app owns the SDK, the wristband
session and the journey, and decides when a stop is visited or skipped. The view draws the journey
it gets, routes to the first stop still to visit and reports what the visitor does. A host app that
uses it needs no `ProximiMap`.

```kotlin
@Composable
fun JourneyMapScreen(sdk: Proximiio, viewModel: JourneyViewModel) {
    // `sdk` is started by the host app, with the wristband's position provider attached.
    // `journey` is owned, stored and moved on by the host app.
    val journey by viewModel.journey.collectAsState()
    ProximiMapView(
        sdk = sdk,
        journey = journey,
        onJourneyChange = viewModel::setJourney,   // only with Option.PROGRESS
        onEvent = { event ->
            when (event) {
                // A swipe on a stop in the list. stop.id is the POI id, stop.artworkId the
                // collection id. The host app asks, then sets the stop to SKIPPED.
                is ProximiMapEvent.SkipRequested -> viewModel.skip(event.stop)
                else -> Unit
            }
        },
    )
}

// A new journey from the audio guide: every stop pending.
val journey = ProximiJourney.ofArtworks(listOf("GAD.2010.00033", "GAD.2018.00034"), JourneyKind.PERSONALISED)
// The visitor heard the artwork: the host app marks it visited, the route leads to the next stop.
val heard = journey.copy(stops = journey.stops.mapIndexed { i, stop -> if (i == 0) stop.copy(state = ProximiJourney.State.VISITED) else stop })
```

| Parameter | |
|---|---|
| `sdk` | The host app's `Proximiio`, started, with the position provider of its wristband session attached. |
| `journey` | The `ProximiJourney` to show. The view applies every change to the open screen. |
| `onJourneyChange` | The journey with the view's changes, only with `Option.PROGRESS`. Pass it back as `journey`. |
| `modifier` | The view fills it. |
| `options` | `Set<ProximiMapView.Option>`, default none. See below. The view reads them when it opens; a change applies to the next view. |
| `settings` | `GadSettings`, default `STANDARD`. The view reads them when it opens. `postsGeofenceNotifications` and `notifiesGeofenceEvents` do not apply. |
| `routeAccessibility` | The visitor's accessibility needs for routes, default `NONE`. See "Accessibility". |
| `mode` | `VisitMode.MAP` (default) or `LIST` when the view opens. |
| `onEvent` | `ProximiMapEvent`s of the journey screen, on the main thread. |
| `onClose` | Back on the bottom bar, "Leave the tour" and the close button of Journey Complete. Default `null`: the bottom bar has no Back, "Leave the tour here" never shows, the close button of Journey Complete returns to the map, and the system Back stays with the host app. |
| `onNavigator` | The map library's `JourneyNavigator` that draws the journey, on the main thread, before the journey starts and again each time the screen starts again. See "The navigator". |

The host app:

- creates and starts the SDK and attaches the wristband's position provider. The view does not
  start, stop or configure the SDK and does not change the wristband session;
- uses the SDK and Map versions this library depends on (see "Adding the library");
- turns on the SDK's privacy-zone option and passes the wristband's privacy signal to the SDK (see
  "Privacy zones");
- lays the view out and closes it; draws the background and the bottom bar (for example the audio
  guide's controls);
- stores the journey and moves it on. The view stores nothing but the accessible routes button.

The view installs the route network from the SDK's feature cache when it opens, or downloads it when
the cache has no path. While it is on screen it follows the SDK's `venueDataChanges()` and downloads
nothing (see "Venue data changes"). It requests no permission and posts no notification. Its state
survives a recreated activity, for example after a language change.

### Options

Each option turns on something the view does beyond drawing the journey. `ProximiMap` uses
`ProximiMapView.Option.ALL`.

| `ProximiMapView.Option` | With it | Without it (default) |
|---|---|---|
| `BOTTOM_BAR` | A bar with Back (with `onClose`) and a Map / List button. | No bar. The host app draws its own. |
| `BACKGROUND` | The library's background image around the map card. | Transparent. |
| `PROMPTS` | All four prompts below (iOS `.prompts`). | Only the prompts named one by one. |
| `SKIP_PROMPT` | "Skip this artwork?" after a swipe. "Skip artwork" reports `StopSkipped`; the stop is skipped with `PROGRESS`, else the host app skips it. | A swipe reports `SkipRequested` only. |
| `OFF_ROUTE_PROMPT` | "Looks like you've left your route" with "Dismiss", in place of the stop card, `GadSettings.offRouteMeters` (12 m) off the leg to the current destination, also where a later leg passes, and before any routing. It stays `GadSettings.offRouteWarningDuration` (10 s). With `PROGRESS` a personalised journey keeps the leg until another open artwork is nearer, then the stops ahead are ordered again; other journeys are routed again from the visitor. A floor the route does not take counts as off the route after `GadSettings.wrongFloorAfter` (10 s). See "Journeys". | The route follows a visitor who left it; `OffRoute` reports it. |
| `UNCERTAIN_POSITION_PROMPT` | "Probably at …" when the position is uncertain near the active stop. | `PositionUncertain` reports it. |
| `LEAVE_TOUR_PROMPT` | "Leave the tour here" after Back on the bottom bar during a journey in progress (with `onClose`). | Back calls `onClose` at once. |
| `PROGRESS` | The view moves the journey on and reports it through `onJourneyChange`: an artwork is visited when the visitor leaves its geofence (`ArtworkReached`, `GadSettings.advancesOnArtworkExit`) or, in a personalised journey, stays at it out of order (`ArtworkVisitedOutOfOrder`); a personalised journey is ordered floor by floor and gallery by gallery; a stop whose POI is deleted is skipped. | Only the host app's journey changes the stops. The view keeps the host app's order and reports no journey. |
| `JOURNEY_NOTIFICATION` | The ongoing journey notification (iOS `.liveActivity`). It needs the notification permission; the view asks for it. | No journey notification. |
| `JOURNEY_COMPLETE` | Journey Complete when the last stop is done. | No screen; `JourneyFinished` reports the end. |

### ProximiJourney

| Member | |
|---|---|
| `kind` | `PERSONALISED` or `CURATED`. See "Journeys". |
| `stops` | `List<ProximiJourney.Stop>` in visit order. A stop has the `id` the host app gave (a collection id `remote_id` or a Proximi.io POI id) and a `state`: `PENDING`, `VISITED` or `SKIPPED`. |
| `ProximiJourney.ofArtworks(artworks, kind)` | A journey whose stops are all pending. |
| `ProximiJourney(kind, stops)` | A journey with its progress. |
| `ProximiJourney.ofJourney(journey, kind)` | The stops of a map library `Journey`, with their ids. `DONE` is `VISITED`, `SKIPPED` is `SKIPPED`, `PENDING`, `ACTIVE` and `REACHED` are `PENDING`. Detour stops are left out. |
| `toJson()`, `ProximiJourney.fromJson(text)` | The journey as JSON, the format of the iOS `Codable`: `{"kind": "curated", "stops": [{"id": "GAD.1", "state": "visited"}]}`. `fromJson` returns `null` for unreadable JSON. |

- The route leads to the first pending stop in the journey's order. In a `PERSONALISED` journey it
  leads to the stop's gallery first (see "Gallery first").
- The open screen applies a change of `journey` in place, with the navigator's `update(to)`: a stop
  visited, skipped, pending again, added, removed or moved. The map, the camera, the navigator and
  the route of an unchanged leg stay. Changes apply one after another; while one applies, only the
  latest waits.
- These changes start the screen again (new map session, navigator, camera and mode): a new `kind`, a
  journey without a stop on the map, and the first journey on a map without one, because that map
  has no navigator.
- A journey without stops, or without a stop on the map, shows the map alone: search, floors and
  "Centre on me", no Map / List toggle and no stop card, in either `mode`. "Go here" on an amenity
  draws the route to it.
- A stop whose POI is deleted on the backend while the view is open is reported as
  `StopPlaceDeleted`. Without `PROGRESS` it stays on the screen until the next change of `journey`,
  which leaves it out.
- A stop whose POI changes on the backend while the view is open takes the new title, position,
  floor and gallery (see "Venue data changes").
- A stop whose id matches no POI, and a second stop of the same POI, are not on the map.
- Detours to amenities are not stops of `ProximiJourney`.

With `PROGRESS` the view also reports through `onJourneyChange`:

- the journey after every change of the order or of a stop state. A personalised journey is ordered
  from the visitor at the start, so the first report can be the new order;
- `VISITED` when the visitor leaves the artwork's geofence (`advancesOnArtworkExit`), or
  `artworkAdvanceDelay` after the arrival without it. The stop the route leads to is `PENDING`;
- the stops that match no POI unchanged, after the other stops;
- nothing at Journey Complete: the journey already holds the last states.

### Events of ProximiMapView

`onEvent` receives the journey screen's events of the [Events](#events) table: `TrackingChanged`,
`NextStop`, `StopNearby`, `PositionUncertain`, `StopPlaceDeleted`, `SkipRequested`, the amenity and
detour events, `JourneyResumed`, `OffRoute`, `BackOnRoute`, `JourneyFinished`, the floor without
positioning events, `ModeChanged`, `Recentred`, `AccessibleRoutesChanged`, `JourneyPaused` and
`DestinationUnavailable`; with `SKIP_PROMPT` also `StopSkipped` and `SkipCancelled`; with `PROGRESS`
also `ArtworkReached`, `ArtworkVisitedOutOfOrder` and `StopOrderChanged`; with `BOTTOM_BAR`,
`LEAVE_TOUR_PROMPT` and `onClose` also the exit events.

`StopOrderChanged(journey)` comes after `onJourneyChange` got a new order of the stops: from the
visitor's first position, after `ArtworkVisitedOutOfOrder` and after a detour. A change of the states
alone sends none. Without `PROGRESS` the view keeps the host app's order and never sends it. Screen, wristband, arrival, geofence and `JourneyChanged` events come only from
`ProximiMap`; geofence enter and exit events are on the host app's SDK (`geofenceEvents()`).

### The navigator

`onNavigator` gives the host app the map library's `JourneyNavigator` that draws the journey. The
host app collects its `events` and sets its `onCue` as on a navigator of its own.

```kotlin
var navigatorJob: Job? = null   // for example in the host app's ViewModel

ProximiMapView(sdk = sdk, journey = journey, onJourneyChange = { journey = it }, onNavigator = { navigator ->
    navigatorJob?.cancel()      // a new navigator replaces the previous one
    // UNDISPATCHED: the collection starts inside the call and gets every later event.
    navigatorJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
        navigator.events.collect { event ->
            when (event) {
                is JourneyEvent.StopSkipped -> println("skipped ${event.stop.id}")
                is JourneyEvent.OffRouteTooLong -> println("off route for ${event.duration} s")
                JourneyEvent.JourneyFinished -> println("finished")
                else -> Unit
            }
        }
    }
    navigator.onCue = { cue ->
        if (cue.kind == RouteGuidanceCue.Kind.APPROACHING_DESTINATION) println("approaching: ${cue.text}")
    }
})
```

- The view calls `onNavigator` before the journey starts. Each time the screen starts again (see
  "ProximiJourney") it calls it with a new navigator. The `events` flow of the previous navigator
  never completes: cancel its collection. A change of `journey` that the open screen takes goes to
  `update(to)` of the same navigator. A recreated activity keeps the navigator and does not call
  `onNavigator` again.
- `events` is a shared flow: every collector gets every event after it started collecting. The
  view's own collection takes no events from the host app's.
- The view sets neither `onCue` nor `cueRules`. `cueRules` is the map library's default
  (`RouteGuidanceCueRules.VENUE_WALK`: a cue 15 m before each manoeuvre).
- The host app changes the journey through `journey` only. The view does not see calls that change
  the navigator (`markDone(stopId)`, `skip(stopId)`, `reopen(stopId)`, `add`, `move`, `apply`,
  `detour`, `resumeJourney()`, `setRouteOptions`), and its next change of the journey can undo
  them.

The view's navigator differs from a navigator with the map library's default rules:

| | The view's navigator |
|---|---|
| `StopSkipped`, `LegChanged`, `JourneyFinished` | Sent when the view applies a change of `journey` (`update(to)`). |
| `LeftRoute`, `OffRouteTooLong` | After `GadSettings.offRouteMeters` (12 m, 3 fixes; `leftRouteMeters`) and `GadSettings.offRouteAfter` (300 s). |
| Off the route | Without `OFF_ROUTE_PROMPT` the route follows the visitor (`JourneyDeviationPolicy.AUTOMATIC`); with it the navigator routes nothing by itself (`ASK_APP`) and the view shows its warning first. While the journey is paused the view passes on no `LeftRoute`, `FarFromRoute`, `OffRouteTooLong` or `ReturnedToRoute`. |
| `StopReached` and the cue `ARRIVED` | Only for a detour to an amenity. A leg to an artwork never reports an arrival (`arrivalDwellSeconds` is infinite): the visitor reaches an artwork by entering its geofence, which the host app's `geofenceEvents()` reports. |
| The cue `APPROACHING_DESTINATION` | 15 m before the end of the leg. In a personalised journey a leg to an artwork in another gallery ends at that gallery (see "Gallery first"); other legs end at the artwork. |

### Routing to a place

`destination` routes the visitor to a POI at once, for example to the place of the audio guide's
proximiio-mcp answer (`route_to_place`, `route_to_nearest_amenity`). The visitor agreed with the host
app, so the view shows no card and no prompt.

```kotlin
var destination by remember { mutableStateOf<ProximiDestination?>(null) }

ProximiMapView(
    sdk = sdk, journey = journey, onJourneyChange = { journey = it },
    destination = destination, onDestinationChange = { destination = it },
    onEvent = { event ->
        if (event is ProximiMapEvent.DestinationUnavailable) println("${event.poiId}: ${event.reason}")
    },
)
// The audio guide's answer:
destination = ProximiDestination(poiId = "c1f9…")   // feature id or properties.id
```

- During a journey the place is a detour: `DetourStarted`, then `DetourEnded` when the visitor
  reached it or left it, and "Resume your Journey" on the stop card. A stop of the journey still to
  visit becomes the next stop instead. A place that is already a stop gets the stop id
  `detour:<poi id>`.
- Without stops the map shows the route from the visitor, as "Go here".
- The view sets the destination back to `null` through `onDestinationChange` when the route ends:
  "Resume your Journey", "Close", another place picked on the map, or `DestinationUnavailable`.
  `null` from the host app ends the route.
- `DestinationUnavailable(poiId, reason)`: `UNKNOWN_PLACE` (no place of the venue has the id),
  `NO_ROUTE` (no route from the visitor with the accessibility needs) or `NO_POSITION` (a map without
  a journey has no position yet).
- The id is the feature id (`<organisation id>:<properties.id>` on GAD data) or its `properties.id`,
  which proximiio-mcp returns.

### Pausing the journey

`isPaused = true` pauses the journey, for example from the audio guide's pause button
(`onPausedChange` hands back `false`):

- The dot moves and the route stays. No artwork is visited, gallery first does not draw the leg
  again, and no off-route warning, closer artwork or wrong floor shows. The navigator leaves the leg
  where it is (`ASK_APP`).
- The view reports no `OffRoute`, `BackOnRoute` or `PositionUncertain`.
- The stop card shows "Journey paused", "Your next stop is …." and "Resume your Journey". A detour
  keeps its leg and its arrival.
- `isPaused = false`: the leg is drawn again from the visitor; with `PROGRESS` a personalised journey
  is ordered again from the visitor first.
- "Resume your Journey" on the card sets it back to `false` through `onPausedChange` and reports
  `JourneyResumed`. A change of the host app reports no event.
- "Add a stop" on an amenity reports `DetourStarted` and `JourneyPaused`; the journey waits for
  "Resume your Journey" (`JourneyResumed`). `isPaused` does not change for it.

### Preparing the view

The first `ProximiMapView` or `ProximiExploreView` on an SDK installs the route network and reads the
venue's features, geofences and amenities into memory. The later views on the same SDK reuse them, and
the data follows `venueDataChanges()` also while no view is open. After the SDK stopped, the next view
starts again. Optional, after the SDK started, for example when the visitor finishes onboarding:

```kotlin
scope.launch { ProximiMapView.prepare(context, sdk) }
```

The first view then opens without that work; the map library's own read of the features waits behind
it otherwise.

## Screens

`ProximiMapHost(map, modifier, content)` shows the screen `open(screen)` asks for over `content`, the
host app's UI. Compose it once, at the root. Nothing opens before `start()` has finished.

| `ProximiMap.Screen` | Shows | Inside the flow |
|---|---|---|
| `MUSEUM_MAP` | The Explore Museum map, without the visitor's position: "Level N" and the floor changer, the galleries in the level colour, the Spaces, Highlights and Amenities lists, and a card for a picked place (see "Explore Museum"). | Back closes it. |
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
| Route and stop card | Lead to the gallery of the next artwork until the visitor is in it, then to the artwork (see "Gallery first"). | Lead to the artwork. |
| List | Stops under gallery headers. | Stops without headers. |
| Skip | The visitor can skip an artwork, also one no accessible route reaches. | No skip. |
| Order | Ordered floor by floor and gallery by gallery from the visitor, at the start and after a detour or an artwork visited out of order. | The curators' order. |
| Another artwork | An artwork further on that the visitor stays at counts as visited (`ArtworkVisitedOutOfOrder`). | Ignored: only the next artwork counts. An artwork the visitor passes stays open. |

The map and the background are the same for both.

### Gallery first

In a personalised journey, a leg to an artwork in a gallery the visitor is not in ends at that
gallery. The artwork's `metadata.gallery_id` names the gallery: the gallery geofence
(`metadata.geofence_type` `gallery`) and the gallery's POI both have it in `remote_id`. The route ends
at the POI. The map fills the geofence's outline with the floor's colour and marks the POI with a red
label, the first word of its title ("G303"); the POI's own icon is hidden. The stop card names the
POI ("G303 Language") with a gallery icon and no stop number.

The first fix inside the gallery geofence routes the leg again from the visitor, to the artwork. A
leg that starts in the artwork's gallery ends at the artwork. An artwork without `gallery_id`, or
whose gallery has no geofence polygon or no POI, is routed to directly. The GAD venue data on
2026-10-19 has 14 such galleries.

An artwork on another floor than its gallery, or more than `GadSettings.galleryFirstMarginMeters`
(50 m) outside the gallery's geofence, is routed to directly. In the GAD data of 2026-10-19, 34
artworks lie 5–29 m outside their gallery's geofence. When the gallery has no route from the
visitor, the leg ends at the artwork, and so do the later legs to that artwork. The field log names
both cases ("gallery first:").

### Venue data changes

The backend changes POIs, paths, lifts and geofences while the app runs. The SDK's audit sync applies
the changes every `ProximiioConfiguration.syncInterval` (300 s by default). The screens follow
`Proximiio.venueDataChanges()` (SDK 6.0.0-beta.27) and download nothing themselves. The SDK names
changed features only while its store holds the whole feature collection, that is after a
`loadRouteNetwork()` on this install. After a change:

- The open map draws the new POIs, amenity icons, floors, places and geofences: the map library
  follows `venueDataChanges()` (map 6.0.0-beta.23). Camera, floor, route and journey stay.
- The SDK has rebuilt the route network from the changed features. The open journey routes its legs
  again (`JourneyNavigator.routeNetworkChanged()`); a leg on the line already drawn stays as it is.
- The stop picker, the Explore Museum lists, the journey list, the galleries, the artwork geofences
  and the lift outlines use the new data.
- On the open journey screen a stop whose POI changed takes the new title, position, floor and
  gallery (`JourneyNavigator.replace`), and the leg to it is routed again when it moved. A reached
  or done stop keeps its values.
- On the open journey screen of `ProximiMap` (and of `ProximiMapView` with `PROGRESS`), a stop whose
  POI was deleted is skipped and reported as `StopPlaceDeleted`. The stored journey of `ProximiMap`
  removes it when the screen closes.
- The stored journey of `ProximiMap` follows its POIs. A done stop keeps its title and position.
  Another stop whose POI was deleted is removed; another stop whose POI changed takes the new title,
  position, floor and gallery. A journey with no stop left is deleted.
  `ProximiMapEvent.JourneyChanged` follows.

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

## Privacy zones

Privacy zones are drawn in the Proximi portal (Privacy zones), for example over the restrooms and the
prayer room. These rooms have no positioning anchors, so the wristband falls silent at the zone.

- relay-api (0.17.0 and later) syncs the organization's privacy zones every hour. A band that falls
  silent with its last position inside a privacy zone, or within the site's `privacy_margin_m` (3 m by
  default), gets the signal `private` instead of `lost`. While private, relay-api does not close the
  binding for silence (`TIMEOUT`) or for leaving the venue (`LEFT_VENUE`). The other end reasons stay.
- The SDK (6.0.0-beta.31) is private (`privacyZoneState()`, `privacyZoneStateChanges()`) while its
  last fix is inside a privacy zone or the relay says `private`. With
  `ProximiioConfiguration.withholdsPositionsInPrivacyZones`, `positions()` emits no fix while private,
  and positioning stays available.
- The map (6.0.0-beta.27) holds the dot at the last fix before the zone, grey
  (`PositionStyle.privacy`), with the accuracy ring of that fix. While private the journey reports no
  off-route, re-route or arrival.
- The journey screen reports `TrackingChanged(PRIVACY_ZONE)` and no `LOST_PLACE` or
  `STOPPED_RESPONDING` while private.

`ProximiMap` turns the option on and passes the binding's signal to its SDK. A host app with
`ProximiMapView` does both on its own SDK:

```kotlin
val configuration = ProximiioConfiguration.relayOnly(token = token).copy(
    withholdsPositionsInPrivacyZones = true,
)

// In a coroutine that follows the wristband binding's states:
binding.stateChanges().collect { state ->
    val signal = (state as? BlueiotBindingState.Active)?.signal
    sdk.setPositioningSourcePrivate(since = (signal as? BlueiotBindingSignal.PrivacyZone)?.since)
}
```

## Explore Museum

`open(Screen.MUSEUM_MAP)` shows the Explore Museum map (Figma "Explore Museum", node 12129:102006)
with the library's title "Explore Museum" and Back. `ProximiExploreView` is the same map as a UI
component on the host app's started SDK, without title, Back and background: the host app draws
them. A host app that uses it needs no `ProximiMap`.

```kotlin
ProximiExploreView(sdk, Modifier.fillMaxSize())       // settings: GadSettings, default STANDARD
```

`ProximiExploreView` reads the venue data the SDK holds and follows its `venueDataChanges()`. It does
not start, stop or configure the SDK and reports no event. It reads `settings` (the zoom range and
the map's loading timeout) when it opens.

The visitor is not at the museum there: the map has no position dot, no route, and its camera does
not follow.

- At the top: "Level N" and the floor changer (the level in a tile of the level colour). The changer
  lists the museum's levels. The map opens on the lowest level with a gallery, framed on the museum.
- The galleries of the shown level are filled with the level colour (`Palette.level`). Artworks
  have no marker.
- At the bottom: the legend button and the tabs Spaces, Highlights and Amenities. A tab opens its
  list over the map, grouped by level; the legend button opens the map legend. A second tap, a drag
  down on the panel's top, or the system Back closes it. The rows fade out above the tab bar.
  - Spaces: the galleries. The gallery geofence (`geofence_type` gallery) gives the outline, the POI
    with the same `remote_id` the name, the level, the category (its amenity, such as "Thematic
    Gallery") and the description.
  - Highlights: the artworks that list the "Highlight" amenity in `included_amenities`, with
    `metadata.artist_name` and the gallery.
  - Amenities: restrooms, café, help desk, prayer room and shop, by level, then by the amenity's
    `metadata.facilities_order`. Stairs and lifts are not listed. See "Amenity lists".
  - Only the museum's POIs are listed: the `place_id` of the gallery POIs. The GAD organisation also
    holds the DCT office.
- The map legend has two columns. Facilities: the amenities the museum's POIs use, other than
  artworks, with "Elevator" and "Stairs" when the venue has lifts or staircases. Galleries: the
  amenities of the spaces and the amenities named "… Gallery", "… Exhibition" or "Learning …".
- A row, a card and the legend show the library's icon of a restroom, café, help desk, prayer room,
  shop, lift or stairs, else the icon of the POI's amenity in the venue data (the icon the map draws),
  else a symbol for the gallery's category.
- A row, or a tap on a gallery or a listed POI on the map, shows its level and a card with its icon,
  title, lines and level. With a gallery's card open, the other galleries are lighter. An amenity gets
  the red marker with its icon.
- The design's About the Building and Collections tabs are not in the library: their content is not in
  the venue data.

The map of Journey Complete (the map button of its bottom bar) is the museum map with the visitor's
position, search and routes.

### Amenity lists

A place is of an amenity kind (restroom, café, help desk, prayer room, shop, stairs, lift) by its
amenity: the amenity's title names the kind. The POI's own title is never matched, so the artwork
"Endless Prayers XIII" is no prayer room. The amenity's optional `metadata` chooses and orders the
lists:

| Keys | List |
|---|---|
| `in_search`, `search_order` | The amenity shortcuts of the journey map's and the museum map's search. |
| `in_facilities`, `facilities_order` | The Explore Museum Amenities list (within a level) and the legend's facilities. A lift or stairs entry ranks as the amenity titled "elevator" or "staircase". |

- When an amenity has the flag `true` (or "true"), the list shows only the amenities with the flag
  true. Without one, the list shows its own choice (for the search: women's and men's restroom, café,
  help desk, prayer room, museum shop) without the amenities whose flag is `false`.
- The order is a number or a numeric string, lowest first. An amenity without an order comes after
  the ordered ones, in the list's own order. The legend's galleries keep the data's order.

## Events

`ProximiMap` performs every action itself; the events report them. They are sent on the main thread.

| Event | When |
|---|---|
| `Opened(screen)`, `Closed(screen)` | A screen opened or closed, by `open(screen)` or inside a flow. |
| `CraftNewJourneyRequested` | "Craft a new journey" on Manage journey, after it closed. The host app shows its journey crafting. |
| `CuratedTourChangeRequested` | "Edit stops" on Manage journey for a curated tour, after it closed. The host app shows its tour choice. |
| `ArrivedAtMuseum` | `isAtMuseum` became `true`. Once per `ProximiMap`. |
| `WristbandChanged(status)` | The wristband session changed. |
| `TrackingChanged(health)` | During a journey: the wristband's positions stopped (`LOST_PLACE` after `trackingLostAfter`, `STOPPED_RESPONDING` after `trackingStoppedAfter`), came back (`TRACKING`), or the visitor is in a privacy zone (`PRIVACY_ZONE`, never lost there). |
| `GeofenceEntered(geofence)`, `GeofenceExited(geofence)` | Every geofence enter and exit, with or without a journey. An artwork geofence has `type` `artwork` and the collection id in `artworkId`. The exit comes `geofenceExitDelay` (5 s) after the visitor leaves. |
| `JourneyChanged(journey)` | A new, planned, edited or deleted (`null`) journey, and the progress after the journey screen closed. |
| `NextStop(stop)` | The route leads to this stop now. |
| `StopNearby(stop)` | The active stop is less than `stopNearbyMeters` away on the visitor's level. Once per stop. The library posts no notification for it. |
| `PositionUncertain(stop)` | `ProximiMapView` without `PROMPTS`: the position is uncertain near the active stop, where the prompt would ask "Probably at …". At most once per `locationQuestionQuietPeriod`. |
| `StopPlaceDeleted(stop)` | The stop's POI was deleted on the backend while the journey screen is open. Once per stop. See "Venue data changes". |
| `ArtworkReached(stop)` | The visitor was in the artwork geofence of the active stop (they entered it, a fix lies in it, or the SDK counts them inside it: `enteredGeofenceIds`, also for a journey opened inside it) and left it: on the SDK's exit event, after `geofenceExitDelay` outside it without one, or at a fix in another open artwork's geofence (`advancesOnArtworkExit`). Without that setting: `artworkAdvanceDelay` after the arrival. The stop is done and the route leads to the next stop. `GeofenceEntered` arrives at the enter. |
| `ArtworkVisitedOutOfOrder(stop)` | Personalised journeys only: the visitor stayed at an artwork further down the journey. It counts as visited, and the stops ahead are ordered again. A curated tour ignores such an artwork. |
| `SkipRequested`, `StopSkipped`, `SkipCancelled` (`stop`) | A swipe on a stop in the list (or its "Skip" TalkBack action), "Skip artwork", "Keep it". `SkipUndone` is deprecated and not sent: the list has no "Undo skip". |
| `AmenitySelected(stop)` | An amenity picked on the map. A card shows it with "Go here"; the amenity gets the red marker with its white icon, and the map shows the visitor and the amenity (on another floor: the amenity's floor around it). |
| `DetourStarted(stop)`, `DetourEnded(stop, completed)` | "Change your route?" confirmed; the detour reached (`completed`) or left. |
| `JourneyResumed` | "Resume your Journey" or "Show the route from here". |
| `OffRoute(duration)`, `BackOnRoute` | The visitor has been off the route for `offRouteAfter`; the visitor is on the route again. |
| `JourneyFinished` | Every stop is visited or skipped. Journey Complete shows (`ProximiMap`, and `ProximiMapView` with `JOURNEY_COMPLETE`). |
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
| `advancesOnArtworkExit` | `true` | The route leads to the next stop when the visitor leaves the active artwork's geofence (Nagarro, 2026-10-30). `false`: `artworkAdvanceDelay` after entering it. |
| `artworkAdvanceDelay` | 2 s | With `advancesOnArtworkExit` `false`: from the arrival at an artwork (its geofence) to the route to the next stop. |
| `outOfOrderDwell` | 0 s | Time inside another artwork's geofence before it counts as visited out of order (personalised journeys only). |
| `amenityArrivalMinimumMeters`, `amenityArrivalMaximumMeters`, `amenityArrivalDwell` | 5 m, 10 m, 5 s | An amenity is reached within 2.5 × the position accuracy, within these bounds, for this time. |
| `geofenceExitDelay` | 5 s | Time the position stays outside an entered geofence or privacy zone before the SDK reports the exit. A position back inside within it reports nothing. `ProximiMap` sets it in the SDK it starts; `ProximiMapView` runs on the host app's SDK and its `ProximiioConfiguration.geofenceExitDelay` (SDK default 3 s). |
| `stopNearbyMeters` | 5 m | The distance of `ProximiMapEvent.StopNearby`. |
| `offRouteMeters`, `offRouteAfter` | 12 m, 300 s | "Looks like you've left your route": this far from the leg to the current destination, also where a later leg passes, or off the route this long. |
| `routeSnapMeters` | 0.5 m | The dot is drawn on the route within this distance. Display only. |
| `followZoom`, `galleryFollowZoom` | 18.8, 20 | Zoom of "Centre on me" and of the follow; in a gallery of a personalised journey, `galleryFollowZoom` (`null` for `followZoom`). |
| `startZoom`, `startZoomDuration` | 18, 2 s | When the journey starts, on the first fix and when the position comes back, the journey map shows the visitor at `startZoom` for `startZoomDuration`, then follows them. `null` follows at once. |
| `minimumZoom`, `maximumZoom` | `null` | The farthest every map of the library zooms out and in (MapLibre zoom levels). `null` keeps the map library's limit. |
| `groupsStopMarkers` | `true` | Journey stop markers that would overlap show as one marker with a badge holding the number of the others (PM, Monday "Clustering", 2026-10-29). |
| `bearingSettlingTime`, `bearingDeadband` | 1.5 s, 5° | The heading-up map takes `bearingSettlingTime` to turn to a new heading and does not turn for a change smaller than `bearingDeadband` (Nagarro: the rotation was too fast, 2026-10-29). The map library's defaults are 0.9 s and 0°. |
| `smoothsPosition` | `false` | `false` draws the position dot on each fix. `true` slides it toward each fix, which shows a fix a moment later. |
| `mapLoadingTimeout` | 20 s | The longest time the maps show their spinner while the map library draws the venue. |
| `amenityFramingMinimumMeters` | 40 m | The smallest area the map shows for an amenity picked in the menu. |
| `galleryFirstMarginMeters` | 50 m | Gallery first: an artwork farther than this outside its gallery's geofence, or on another floor, is routed to directly. |
| `liftRideDwell`, `liftExitFixes` | 3 s, 2 | Time of fixes inside a lift outline before the visitor counts as riding it, and the fixes outside that end the ride. The field log records the rides. |
| `levelsWithoutPositioning` | [0] | Floors without positioning. See "Floors without positioning". |
| `levelChangerReachedMeters` | 5 m | Distance from the lift or stairs to a floor without positioning at which the lift or stairs view shows. |
| `levelChangeViewDuration` | 5 s | How long the lift or stairs view shows before the map shows the floor without positioning. |
| `rideFloorHoldMeters` | 20 m | After the lift or stairs view of a ride, the map shows the floor the ride ends on until a fix on another floor than the ride's start, or a fix on that floor this far from the lift or stairs. |
| `floorWithoutPositioningMaximum` | 1800 s | After this time on a floor without positioning with the same destination, the map follows the visitor again. |
| `uncertainAccuracyMeters`, `locationQuestionQuietPeriod` | 8 m, 120 s | "Probably at …": an uncertain position near the next stop, and how long an answered prompt stays quiet. |
| `firstFixWait`, `lateOrderWindow` | 5 s, 60 s | A new personalised journey waits this long for a fix before it is ordered without one, and the first fix within the window orders it again. |
| `journeyNotificationMinimumInterval` | 5 s | The shortest gap between two updates of the instruction in the journey notification. |
| `postsGeofenceNotifications` | `true` | A notification for every enter and exit of a geofence with a name ("You entered this area.", "You left this area."), and the notification permission request once a wristband is connected. The library posts no "next stop" notification; a host app can post one for `ProximiMapEvent.StopNearby`. |
| `notifiesGeofenceEvents` | `true` | With `postsGeofenceNotifications`: `false` posts no geofence notification; the permission request stays. |
| `venueDataRefreshInterval` | 300 s | Deprecated, no effect since 1.9.0. The SDK's `ProximiioConfiguration.syncInterval` sets how often changes arrive. See "Venue data changes". |

Times are `kotlin.time.Duration`, distances metres.

The geofence notifications use the channel `alerts` (high importance, no sound). A notification
replaces the earlier one of the same geofence and direction; privacy zones post
nothing. The journey notification uses the channel `visit`; the SDK's positioning service has its
own channel. Without the notification permission nothing is shown.

## The journey list

A row shows the artist and "AR" and audio badges from the artwork's POI `properties.metadata`.
Without `artist_name` the row shows the level. The stop card shows the same badges while it names
the artwork.

| Key | Value | Shows |
|---|---|---|
| `artist_name` | String, or an i18n object like `title_i18n` | The artist under the title. |
| `augmented_reality` or `has_ar` | `"enabled"`, `true`, `"true"` or a non-zero number | "AR". |
| `audio` or `has_audio` | `"enabled"`, `true`, `"true"` or a non-zero number | A note. |
| `gallery_id` | The `remote_id` of a gallery POI | The stop's gallery header, and gallery first. |

In the GAD venue data on 2026-10-15 one artwork has `augmented_reality` and `audio` (Tongari-kun).

The header of a stop is its gallery by `gallery_id`, named by the title of the POI whose
`remote_id` is that id ("G403 Weaving"); else the gallery outline it lies in; else its level
("Level 4"). The GAD venue data on 2026-10-15 has `gallery_id` on artworks in 18 galleries; 14 of
the 18 have a gallery POI.

## Floors without positioning

A floor in `GadSettings.levelsWithoutPositioning` has no positioning. In stage 1 that is the GAD
level 0, with the café and the prayer room. A route there, on the journey map and the museum map of
Journey Complete:

1. leads to the lift or stairs as usual;
2. within `levelChangerReachedMeters` of the lift or stairs, shows the lift or stairs view ("Take the
   stairs to level 0", "Your position is not shown on this level.") for `levelChangeViewDuration`; a
   tap ends it;
3. shows that floor with the rest of the route. The map does not follow the visitor there, no
   off-route warning and no "Probably at …" shows, and the card says that the position is not shown.

The map follows the visitor again when the destination changes ("Resume your Journey", another stop
or route), on "Centre on me" or "Follow my floor", or after `floorWithoutPositioningMaximum`. The
journey screen sends `FloorWithoutPositioningEntered(level)` and `FloorWithoutPositioningLeft(level)`.
The view plays RPR's animation of a lift or stairs and has the test tag `floorChangeView`.

The journey map shows the lift or stairs view at every ride of the route, also to a floor with
positioning. After it the map shows the floor the ride ends on and keeps it until a fix on another
floor than the ride's start, or a fix on that floor more than `rideFloorHoldMeters` from the lift or
stairs: the positioning still reports the old floor for a while. "Centre on me" and "Follow my floor"
end it at once. The GAD lifts and stairs are privacy zones, so the dot is hidden during the ride.

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
- The journey map and the museum map of Journey Complete have an accessible routes button (a
  wheelchair, blue while on).
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

`FieldLog` writes relay positions, positions during a visit, tracking health, artwork arrivals,
journey, binding and `ProximiMap` events to `files/fieldlog/gad-debug.log` in app-private storage
(`FieldLog.file`), and to Logcat with the tag `GAD`. It does not write the SDK's geofence events. It
is off by default. Turn it on at launch, before `start()`, in test builds only:

```kotlin
FieldLog.start(context, configuration.secrets)   // the tokens are replaced by <redacted>
Proximiio.logSink = { entry -> FieldLog.log("sdk ${entry.category} ${entry.level.label.lowercase()}: ${entry.message}") }
```

`FieldLog.share(context)` opens the share sheet with the file; `FieldLog.share(context, file)` with
another text file in the same folder, for example an excerpt. A line is `[GAD] <time stamp> <message>`;
the time stamp is ISO 8601 in UTC with the date and milliseconds (`FieldLog.TIME_STAMP`,
`2026-10-19T12:52:10.123Z`). The file is cut to its last 4 MB when it passes 8 MB.

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
| `gad.openMap true` | Opens the Explore Museum map. |
| `gad.routeTo <title>` | The museum map of Journey Complete routes to the first place whose title matches, after the first position. |
| `gad.mapGalleryFills true` | The museum map of Journey Complete fills every gallery. |
| `gad.routeAvoid stairs,escalators,revolvingDoors,inaccessiblePaths` | Sets `routeAccessibility`. |
| `gad.hostedMap true` | With `gad.visit`: opens the journey in `ProximiMapView` on the app's SDK with the default options, as Nagarro's app uses it. The debug screen is the host app: it skips a swiped stop, "Visited" marks the first pending stop visited, and a line at the top (test tag `hostedJourneyStates`) counts the states. Without `gad.visit` the view shows the map alone. With `gad.openMap`: the museum map in `ProximiExploreView`, with "Close map". The debug host also has "Host pause" / "Host resume" (`isPaused`), "Go to café" (`destination` by `properties.id`) and a line of the view's events (test tag `hostedMapEvents`). |
| `gad.hostedPrepare` | With `gad.hostedMap`: `ProximiMapView.prepare` before the view opens. |
| `gad.hostedDestination <title>` | With `gad.hostedMap`: the debug host routes to the place with that title, else the first place whose title contains it (`destination`). |

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
- Every request to `apiBaseUrl` carries the headers `X-Visitor-Id` (the visitor id) and
  `X-Proximiio-SDK` (the SDK version). The API limits the request rate per device by `X-Visitor-Id`.
  A proxy in front of an on-premises API must pass both headers through.
- The Play Console Data safety form of the host app declares precise location and a device
  identifier, sent to Proximi.io, not shared and not used for tracking. The device identifier is the
  anonymous visitor id (Analytics, and the rate limit: App functionality) and the wristband binding
  id, which only the relay-api receives (App functionality).
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
| `ProximiMapView(sdk:journey:…)` with `Binding<ProximiJourney>` | `ProximiMapView(sdk, journey, onJourneyChange, …)` | Compose state hoisting: the host app passes the journey and takes the view's changes in `onJourneyChange`. |
| `ProximiMapView.Options` (`OptionSet`, `.all`) | `Set<ProximiMapView.Option>` (`Option.ALL`) | `.liveActivity` is `JOURNEY_NOTIFICATION`. |
| `ProximiExploreView(sdk:settings:)` | `ProximiExploreView(sdk, modifier, settings)` | |
| `ProximiJourney(kind:artworks:)`, `Codable` | `ProximiJourney.ofArtworks(artworks, kind)`, `toJson()`, `fromJson(text)` | Same JSON. |
| `@_spi(GADApp) ProximiMap.sdk`, `wristbandBindingStates()` | `@ProximiGadAppApi` `sdk`, `wristbandBindingStates()`, `postNotification(…)` | The Proximi GAD app posts its notifications on the library's channel `alerts`. |
| `onNavigator`: the previous navigator's `events` streams finish | The previous navigator's `events` flow never completes | The host app cancels its collection. A recreated activity keeps the navigator and does not call `onNavigator` again. |
| `ProximiJourney(kind:journey:)` | `ProximiJourney.ofJourney(journey, kind)` | |
| SDK 6.0.0-beta.68 and Map 6.0.0-beta.59 | SDK 6.0.0-beta.36 and Map 6.0.0-beta.32 | The same SDK and Map changes on both platforms. The SDK's database cannot go back to an older SDK. |

## Tests

```
./gradlew :proximiio-gadkit:testDebugUnitTest
```

The JVM tests cover the rules without a map or an SDK: `ProximiMap` (artwork ids, the journey
summary, the wristband status, the events, the screen order), the card and instruction text, the
ride merge, the floor without positioning, galleries, level changers, artwork geofences, journey
order and the settings. The UI tests run in the Proximi GAD Android app (`app/src/androidTest`),
against the sandbox.

On GitHub the workflow `Check` (`.github/workflows/check.yml`) runs `./gradlew check assembleDebug`
(lint, the JVM tests and the debug build) for each push to `main` and each pull request, on the
repository's own runner. It has no secrets. A pull request from a fork runs after a maintainer
approves it.

## Changelog

The format follows [Keep a Changelog 1.1](https://keepachangelog.com/en/1.1.0/). Every change of
the library adds an entry under `[Unreleased]` in the same commit. A release moves the entries
under a heading with the version and the date; `[Unreleased]` then says that nothing changed since. A tag `X.Y.0` has the behaviour of the iOS tag
`X.Y.0`, except 1.15.0 (see its entry). Entries before 1.0.0 name the types of the API before
`ProximiMap`.

- **BREAKING:** the host app has to change its code: a public API is renamed or removed, a
  parameter is new and required, a type changes, or a default changes. The entry says what to change.
- **Visible:** the screens or the behaviour change and the API stays the same. The entry says
  whether the host app has to do something.

### [Unreleased]

#### Added (iOS 1.23.0, 1.24.0)

- `ProximiMapView.prepare(context, sdk)`: installs the route network and reads the venue's features,
  geofences and amenities into memory before the first `ProximiMapView` or `ProximiExploreView` on the
  SDK opens. Optional; see "Preparing the view" (Nagarro: the map starts in 2 to 2.5 s).
- `GadSettings.startZoom` (18) and `startZoomDuration` (2 s). **Visible:** when the journey starts, on
  the first fix and when the position comes back, the journey map shows the visitor at zoom 18 for
  2 s, then follows them at the follow zoom. "Centre on me" in the 2 s ends it. `null` follows at once
  (PM, Monday "Zoom level at start + navigation", 2026-10-29).

#### Added (iOS 1.25.0)

- `GadSettings.groupsStopMarkers` (`true`). **Visible:** journey stop markers that would overlap show as
  one: the active stop when it is in the group, else the first in journey order, with a 16 dp black
  badge at its top-right holding the number of the others in white (RPR design; PM, Monday
  "Clustering", 2026-10-29; Map 6.0.0-beta.32). Only stops on the same floor group; a detour never does.
  A tap on a group zooms in until it splits. The markers hidden by gallery first never group. `false`
  draws every marker.
- `GadSettings.bearingSettlingTime` (1.5 s) and `bearingDeadband` (5°). **Visible:** the heading-up map
  turns more slowly and does not turn for a heading change under 5° (Nagarro: the rotation was too fast,
  2026-10-29). The map library's defaults are 0.9 s and 0°. The heading arrow of the position dot stays
  live.

#### Changed (iOS 1.27.1, 1.28.0)

- **Visible:** the journey map screen is laid out as the Figma frame 10889:108085 (Nagarro, 2026-10-30:
  the Map / List control and search sat too close to the drag handle; the owner asked for the screen as
  close to Figma as possible):
  - The Map / List control and search are 32 dp below the map card's top edge (were 22 dp), 17 dp below
    the drag handle.
  - The drag handle is 60 × 6 dp at 20 % black with a 2 dp white stroke outside it, its fill 9 dp below
    the card's top edge.
  - The map card is 3 dp below the status bar (was 8 dp), with a 1 dp white stroke outside its edge and
    the design's shadow outside it (blur 50, y −3, 15 % black).
  - The bottom bar is 11.5 dp under the card, 93.5 dp high, and 21 dp into the navigation bar inset.
  - The controls in the card are 21 dp from its sides, with 12 dp between the buttons and the stop card.
  - The stop card is 23 dp above the card's bottom edge, and its instruction line is 18.5 dp high.
- **Visible:** "Add a stop" sets `isPaused` to `true` itself (`onPausedChange(true)`, before
  `JourneyPaused`), and the view does not treat its own write as the host app's pause: the card shows
  the detour. The host app's `false` then does what "Resume your Journey" on the detour's card does: the
  detour ends (on a floor without positioning the map goes back to the visitor's floor first), with no
  event. "Resume your Journey" on the detour's card, and the end of the detour, set it back to `false`.
  "Add a stop" during the host app's own pause keeps the detour after the resume and sets `isPaused` to
  `true` again. Before, the host app's resume after "Add a stop" left the detour, and its card offered
  "Resume your Journey" again (Nagarro, 2026-10-30). A host app that passes `onPausedChange` back as
  `isPaused`, as the docs ask, needs no change.
- **Visible:** the library's texts follow the host app's language while a view is open. The language
  is the first locale of the composition's `LocalConfiguration`: the one the host app gives
  `ProximiMapView`, `ProximiExploreView` or `ProximiMapHost`, or the activity's (the app language,
  `LocaleManager`). "ar" for Arabic, else "en". The texts, the layout direction (right to left in
  Arabic), the journey notification and the error messages of `ProximiMap` follow it; the map labels
  followed it before. Before, a host app that changed the locale only in its composition got the map
  labels in the new language and the texts in the old one (Nagarro, 2026-10-30: the language changed
  wrongly while the app ran).
- Debuggable builds: the debug host of `gad.hostedMap` has "Locale ar/en" (the view's own locale) and
  "App ar/en" (the app language, Android 13 and later), for the UI tests of the Proximi GAD app.

#### Added (iOS 1.29.0)

- `GadSettings.advancesOnArtworkExit` (`true`). **Visible:** the journey moves to the next stop when the
  visitor leaves the active artwork's geofence, not 2 s after they entered it (Nagarro call, 2026-10-30:
  the next stop comes from the exit event). The audio guide can play while the visitor is at the
  artwork. The visitor leaves on the SDK's `GeofenceEvent.Exited`, after `geofenceExitDelay` (5 s)
  outside it without that event, or at once at a fix in another open artwork's geofence.
  `ArtworkReached` and the stop's `VISITED` come at that moment. While the visitor is at the artwork, the
  card and the route still show it as the next stop. `false` keeps the move `artworkAdvanceDelay` after
  the enter.
- Field log: `artwork geofence arrival: … · next stop at the exit` and `artwork left (<exit event |
  outside for n s | in another artwork>): … · next stop`.

#### Changed (SDK 6.0.0-beta.36, Map 6.0.0-beta.32; iOS 1.25.0, 1.27.0)

- **BREAKING:** the library depends on SDK `6.0.0-beta.36` (was `6.0.0-beta.35`) and Map `6.0.0-beta.32`
  (was `6.0.0-beta.31`). A host app that declares them itself moves to the same versions. No source or
  binary change. The SDK's lists of the stored venue data are read-only: code that changes a returned
  list in place gets `UnsupportedOperationException` (MIGRATION.md section 1).
- The SDK answers repeated reads of the stored venue data from memory until the data changes, and the
  map reads the venue's features once per open, not twice. On the Pixel 9 Pro one `features()` call of
  the GAD venue took about 0.5 s.
- The map asks for no floor-plan tile outside the style's `proximiio:raster:bounds` (the GAD style has
  it since 2026-10-30). Those tiles answered 403 and drew nothing; the first drawn frame waited for them.

#### Changed (iOS 1.23.0 to 1.24.1)

- The screens read the venue's features, geofences and amenities once and keep them in memory; a kind
  is read again only when `venueDataChanges()` names it. The SDK decodes every stored row on each
  `features()` call, and the map library's own read waited behind the screens' reads. A change of the
  amenity catalogue now updates the screens' icons and lists. Field log: `venue data: <n> features read
  in <s>` (also geofences and amenities) and `hosted map: route network <n> paths in <s>`.
- `ProximiMapView` and `ProximiExploreView` keep the route network and the venue data for the next view
  on the same SDK; they no longer stop following `venueDataChanges()` when they close.
- **Visible:** every gallery but the one the visitor is in has a fill on the journey map: the
  destination and the visited galleries as before, every other gallery in the venue style's gallery
  colour (`proximiio-gallery`, #E6E6E6) over its artworks and furniture. The artworks and the
  furniture show only in the visitor's gallery, from zoom 19 (PM, Monday "Artwork + furniture
  polygons", 2026-10-29).
- **Visible:** a curated tour shows the galleries' pills and fills as a personalised journey does; the
  destination is the gallery of the active artwork, unless the visitor is in it (PM, Monday "Gallery
  numbers in gallery navigation", 2026-10-29). A curated tour still routes straight to the artwork.
- **Visible:** a gallery is visited only once the visitor is out of it (PM, Monday "Visited gallery
  visualization", 2026-10-29: "when user exits the gallery").
- **Visible:** a long amenity list under the search button scrolls and fades out at the bottom, above
  the map's buttons, on the journey map and the museum map (PM, Monday "Amenity search when list is
  long", 2026-10-29). A list that fits does not scroll.

#### Changed (iOS 1.25.0 to 1.26.1)

- **Visible:** the galleries' names give way to each other and to the stop markers: of names that
  would overlap, one shows, a visited gallery's first. The destination's name always shows. Zoomed out,
  every name showed over the others (the PM's screenshot, 2026-10-29). The names are in two new layers,
  `gad-gallery-names` and `gad-gallery-destination-name`, above `gad-level-changer`.
- **Visible:** the maps' amenity search lists every amenity with `metadata.in_search` true that has a
  place, also one that is none of the built-in kinds (Women's restroom, Men's restroom, Café, Help
  Desk, Prayer Room, Stairs, Elevator, Museum Shop). Such an amenity shows with its title in the app
  language and its icon from the venue data, in its `search_order`, and the shortcut leads to its
  nearest place. Before, only the built-in kinds showed: "Learning & Engagement", "Single Artist
  Gallery", "Performance Gallery" and "Artwork" did not (PM, 2026-10-29). Without an amenity flagged
  true the shortcuts stay the built-in ones.
- **Visible:** a chip's title stays on one line.
- **Visible:** the journey map puts its camera at the SDK's last position (`Proximiio.lastPosition()`), at
  the start zoom and without animation, and selects that position's floor, before the venue's features
  are on the map. A position older than `GadSettings.trackingLostAfter` is not used. The start zoom's
  move to the visitor has no animation either. Before, on the Pixel 9 Pro, the first fix moved the
  camera from the world view to the visitor, animated, while the map still loaded the venue; the map
  was drawn 2.6 to 2.9 s after that move. Field log: `camera at the last position before the venue`.

#### Fixed (iOS 1.23.0)

- Since 1.22.2 the map did not report the venue as drawn after a floor change: the cones of the other
  floors were hidden raster layers, MapLibre does not load the image source of a hidden layer, and an
  unloaded source keeps every frame from counting as fully rendered. The other floors' cones are now
  removed from the map. Only the field log's `map timing` line was affected.

#### Changed (debuggable builds)

- Debuggable builds: the debug host of `gad.hostedMap` shows its `destination` ("destination none",
  test tag `hostedDestination`), for the UI tests of the Proximi GAD app; `gad.hostedDestination`
  takes the place with that title first, as iOS ("Prayer room" also matched "DCT prayer room"). No
  change for the host app.

### [1.22.2] — 2026-10-29

The behaviour of the iOS package 1.22.2, which includes 1.21.1, 1.22.0 and 1.22.1, on SDK 6.0.0-beta.35
and Map 6.0.0-beta.31: the off-route warning on the leg to the current destination, and the building's
cones with the design's metal gradient. No change for the host app's code. There is no Android 1.21.1,
1.22.0 or 1.22.1.

#### Added (iOS 1.22.0 to 1.22.2)

- **Visible:** the journey map, the Explore Museum map and the museum map fill the building's cones
  (polygon features of type `cone`, nine on each floor) as in the Figma kiosk: the design's floor
  colour #FAFAFB, the metal gradient #D4DEE6, #F8FAFC, #E8EEF2, #CBD6DF, #AEBCC9 across the cone at
  32 %, and a white edge inside the outline (PM, Monday "Cone visualization", 2026-10-29). A map style
  fills a polygon with one colour only, so the library draws each cone as an image over the floor
  plan, under the venue style's galleries (`proximiio-gallery`), so a cone that is a gallery keeps the
  gallery's colour. The style's cone outlines (`proximiio-cones`) are #95A6B5 at 48 %, a hairline:
  0.3 dp at zoom 16, 0.6 dp at 19, 1.2 dp at 21.

#### Changed (iOS 1.21.1)

- **Visible:** "Looks like you've left your route" also shows `GadSettings.offRouteMeters` (12 m) off
  the leg to the current destination, where a later leg of the journey passes. The navigator's
  `LeftRoute` measures against every leg still to walk, so a visitor heading to another artwork got
  no warning (PM's log of 2026-10-28, 22:39 UTC: 56 m off the leg). Once per episode, only with
  `OFF_ROUTE_PROMPT`, not while paused, during a detour or on a floor without positioning; the episode
  ends within two thirds of the distance. A curated tour is then routed again from the visitor, and a
  personalised journey is ordered again when another artwork is nearer, as after `LeftRoute`. The
  legs' guidance rules use `GadSettings.offRouteMeters` (`RouteGuidance.isOffRoute`).

#### Fixed

- **Visible:** Explore Museum: a floor or a place the visitor picks while the venue loads stays. The
  first framing showed the lowest floor with a gallery over it. Explore Museum reads the venue's
  features once per load.

### [1.21.0] — 2026-10-29

The behaviour of the iOS package 1.21.0, on SDK 6.0.0-beta.35 and Map 6.0.0-beta.31: every gallery
as a pill with its title, no destination fill in the visitor's gallery, the ride's end floor held
after the lift or stairs view, Map / List slides with the finger. No change for the host app's code,
except a host app that declares the SDK itself (see "Changed").

#### Added (iOS 1.21.0)

- `GadSettings.rideFloorHoldMeters` (20 m). After the lift or stairs view the map shows the floor the
  ride ends on and keeps it until a fix on another floor than the ride's start, or a fix on that floor
  this far from the lift or stairs (PM, Monday "Elevator/Staircase animation", 2026-10-28). "Centre on
  me" and "Follow my floor" end it. The PM drew privacy zones around the lifts and stairs, so the dot
  is hidden during the ride.
- **Visible:** the Map / List selection slides with the finger to the other part, as on the system's
  segmented control (PM, Monday "Native elements", 2026-10-28 evening). A tap still picks the part
  under the finger.
- Field log: on each Explore Museum load, how many galleries are filled on their polygon and how many
  on their geofence ("explore: 26 galleries · 26 filled on their polygon, 0 on their geofence · 41
  gallery polygons in the venue data"; PM, Monday "Explore Museum view: gallery highlighting",
  2026-10-29).

#### Changed (iOS 1.21.0, SDK 6.0.0-beta.35)

- **Visible:** every gallery's POI is a 30 dp pill with its title: red with a white border and white
  text for the gallery the leg leads to, white with a red outline for a visited gallery, white with a
  black outline for the others. The POIs' own icons are hidden, and the gallery the visitor is in has
  no pill. A curated tour shows every pill in the white style. Before, only the destination and the
  visited galleries had a marker, a visited one the amenity icon in a circle (PM, Monday "Gallery
  numbers in gallery navigation", 2026-10-28).
- **Visible:** the gallery the visitor is in has no destination fill: the venue's own gallery colour
  shows, under the artworks and furniture, with the artwork numbers (PM, Monday "Navigation to a
  gallery", 2026-10-28).
- **BREAKING:** the library depends on SDK `6.0.0-beta.35` (was `6.0.0-beta.34`); Map stays
  `6.0.0-beta.31`. A host app that declares the SDK itself moves to the same version. No source or
  binary change. A configuration that scans for iBeacons or Eddystone (not `relayOnly`, which
  `ProximiMap` uses) now gets beacon events and `bleTrilateration` fixes: MIGRATION.md section 1.

### [1.20.1] — 2026-10-29

The behaviour of the iOS package 1.20.1, which includes 1.15.0 to 1.20.0, on SDK 6.0.0-beta.34 and Map
6.0.0-beta.31: one option per prompt, `destination`, `isPaused`, `StopOrderChanged`, "Add a stop", the
off-route card from 12 m, "Rerouting you…", the way back from a floor without positioning, the lift and
stairs animations, galleries on their polygons, amenity kinds by the amenity, map icons at one dp per
pixel, the Explore Museum card with a line to the place, the offline start and the refused token.
There is no Android 1.16 to 1.20.0.

#### Added (iOS 1.15.0, 1.17.0; Nagarro, 2026-10-26 to 2026-10-28)

- One option per prompt: `ProximiMapView.Option.SKIP_PROMPT`, `OFF_ROUTE_PROMPT`,
  `UNCERTAIN_POSITION_PROMPT` and `LEAVE_TOUR_PROMPT`. `PROMPTS` still turns on all four. Without one,
  the view behaves as without prompts for that prompt only. See "Options".
- `ProximiMapView(destination, onDestinationChange)` and `ProximiDestination`: the host app routes
  the visitor to a POI at once, for example from the audio guide. New event
  `ProximiMapEvent.DestinationUnavailable(poiId, reason)`. See "Routing to a place".
- `ProximiMapView(isPaused, onPausedChange)`: the host app pauses and resumes the journey. New event
  `ProximiMapEvent.JourneyPaused` after "Add a stop". See "Pausing the journey".
- `ProximiMapEvent.StopOrderChanged(journey)`: with `PROGRESS`, after a new order of the stops. See
  "Events of ProximiMapView".
- `GadSettings.offRouteWarningDuration` (10 s), `wrongFloorAfter` (10 s) and `galleryFollowZoom` (20).
- A `when` over `ProximiMapEvent` without `else` adds the three new events.

#### Changed (iOS 1.15.0 to 1.20.1)

- **Visible:** during a journey the amenity card has "Add a stop" and "Close". "Add a stop" starts the
  detour at once and takes the map back to the visitor. "Change your route?" is gone (PM, Monday "Add
  stop confirmation", 2026-10-26).
- **Visible:** off the route, "Looks like you've left your route" / "Your next stop is …." /
  "Dismiss" takes the place of the stop card; the bottom sheet "You've left your route" and "Show the
  route from here" are gone. The warning comes before any routing and stays 10 s. A personalised
  journey keeps its leg until another open artwork is nearer; a curated tour is routed again from the
  visitor. A floor the route does not take counts as off the route after 10 s.
- **BREAKING:** `GadSettings.offRouteMeters` is 12 (was 30): the navigator's `leftRouteMeters`, 3
  fixes (PM, Monday "Personalized tour: off-route and re-route behaviour", 2026-10-28).
- **BREAKING:** `GadSettings.followZoom` is 18.8 (was 21; RPR, Monday "Need to determine correct zoom
  levels on-site", 2026-10-28).
- **Visible:** galleries are filled on their polygon in the venue data (`metadata.gallery_id`, else the
  one around the gallery POI); the gallery geofence still decides whether the visitor is in it. The
  journey map knows every gallery with a `gallery_id`, named by its POI, then the other gallery
  outlines (PM, Monday "Explore Museum view: gallery highlighting", 2026-10-26, and "Visited gallery
  visualization", 2026-10-28).
- **Visible:** a gallery is visited when its artworks of the journey are all visited or skipped and at
  least one is visited; being in the gallery no longer counts. A visited gallery is filled over the
  venue's artworks, furniture and inner walls, and its marker is its amenity's icon in the white
  circle with the red ring of a visited artwork, with its title under it (the customer, Monday
  "Visited gallery visualization", 2026-10-28).
- **Visible:** the gallery the leg ends at (gallery first) is a red pill with a white border and the
  gallery's whole title in white, in place of the red label with its first word; it is filled over the
  inner walls too (RPR, Monday "Gallery numbers in gallery navigation", 2026-10-28).
- **Visible:** the stop card's icon: while the leg ends at a gallery, the icon of the gallery POI's
  amenity; while the next stop is on another floor, the lift or stairs of the next level change from
  the visitor's floor (PM, Monday "Stairs&elevator icon disappeared from instructions", 2026-10-27).
- **Visible:** the map icons from the venue data are one dp per pixel, as MapTap and the portal draw
  them (MapLibre GL JS registers an image at pixel ratio 1), so the style's `icon-size` gives the same
  size on the phone and in MapTap: 120 px at 0.2 is 24 dp. Before, an icon was drawn at the screen's
  density, about a third of that. The design's amenity markers and the Explore Museum gallery
  circles take the data icon's pixel size (PM, 2026-10-27).
- **Visible:** the journey map draws the stop numbers, the detour included, over the visitor's dot
  (`drawsAbovePosition`, Map 6.0.0-beta.31; RPR, Monday "Stop markers.", 2026-10-28).
- **Visible:** the top 44 dp of the journey map card do not pan the map; with `BOTTOM_BAR` and
  `onClose` a drag down there is Back (PM, Monday "More space for the pull down functionality",
  2026-10-26).
- Field log: each wristband bind names the band as scanned and the relay's binding ("wristband bind:
  tag <label> · binding <id>", or "· failed: <error>"), so the relay team can look up the band without
  asking the tester (relay-api, 2026-10-28; iOS 1.20.1).
- Field log: the state of each map when it changes, checked every second ("map state journey:
  features … · dropped … · style … · L1 · zoom … · camera ready …", with the style's missing fonts);
  the time of `start()` ("map: ready · … · start 4.92 s") and of each map until it draws the venue
  ("map timing journey: … · drawn 1.80 s, 2.82 s after open()"; `venueDrawing`).
- **Visible:** the journey map's Map / List control is two buttons on glass, 150 × 50 dp with 16 dp
  corners; the selected part is white at 60 % with a 1.5 dp ink outline (PM, Monday "Native
  elements", 2026-10-28). It replaces the Material segmented button of 1.15.0.
- **Visible:** the glass buttons and the search shortcuts have a 1 dp ink outline while pressed, over
  the ripple (PM, Monday "Buttons to have black border when pressed", 2026-10-28). The accessible
  routes button, when on, has the ink outline and no blue fill (RPR, Monday "Accessible routing").
- **Visible:** the search shortcuts have a white haze behind them (RPR, Monday "White blur behind
  amenity list", 2026-10-28).
- **Visible:** the journey map fades out under the controls at the top (110 dp) and over the card at
  the bottom (170 dp; RPR, Monday "Map fade at top & bottom", 2026-10-28).
- **Visible:** the floor on the journey map is a label, not a button (PM, Monday "Current level
  button", 2026-10-28). "Centre on me" follows the visitor's floor.
- **Visible:** "Skip this artwork?" has 12 dp under "Keep it" (was 40; PM, Monday "Skip artwork popup
  too large", 2026-10-28).
- **Visible:** the lift or stairs view plays RPR's animation of a lift or stairs, going up or down,
  looped, in place of the icon in a circle (PM, Monday "Elevator/Staircase animation", 2026-10-27).
  The library depends on `com.airbnb.android:lottie-compose` 6.7.1 (`implementation`); a host app
  that declares another Lottie version gets the higher one.
- **Visible:** the lift or stairs view shows at every ride of the route, within
  `GadSettings.levelChangerReachedMeters` of the lift or stairs, for `levelChangeViewDuration`, once
  per lift or stairs and floors. "Your position is not shown on this level." only on the way to a
  floor without positioning (PM, Monday "Elevator/Staircase animation", 2026-10-28).
- **Visible:** Explore Museum: a picked place's card is over the map, with a line down to the place,
  drawn as a black dot in a ring with its title under it; the map frames the place at 31 % / 75 % of
  the map. A selected amenity is that dot too, not the red marker. The amenities' own icons are not
  drawn on this map (PM, Monday "Explore Museum view: gallery POI view", 2026-10-26 and 2026-10-27).
- **Visible:** Explore Museum: the floor changer opens a column of glass buttons, one per level,
  highest first, the shown level in bold, and a chevron that closes it (PM, Monday "Explore museum:
  Level selector dropdown", 2026-10-28).
- **Visible:** Explore Museum: "Level N" is Regular 24, the card's title Bold 18 and its text Regular
  16, the legend's title SemiBold 14; the lists fade out over 90 dp above the tab bar (was 30). New
  `GadTextStyle.BodyRegular` and `BodySmallSemiBold`.
- **Visible:** on the Explore Museum map a gallery's icon is the data icon on a white circle at 30 %
  with a white border (PM, Monday "Explore museum view: default map view", 2026-10-26).
- **Visible:** a place matches an amenity kind by its amenity, not by its title: the prayer room
  shortcut led to the artwork "Endless Prayers XIII" (PM, Monday "Amenity search & Map legend views",
  2026-10-28). The search shortcuts, the Explore Museum amenities, the legend, the detour icon and the
  card subtitle use it.
- **Visible:** the amenity's `metadata` chooses and orders the search shortcuts (`in_search`,
  `search_order`) and the Explore Museum facilities (`in_facilities`, `facilities_order`). On the GAD
  data of 2026-10-28 only Museum Shop has `in_search` true, so it is the only shortcut. See "Amenity
  lists".
- **Visible:** an Explore Museum space's text is the POI's description, else its category (PM, Monday
  "Explore Museum view: gallery POI view", 2026-10-26).
- **Visible:** an artwork visited out of order moves before the stops still to visit, so the numbers
  in the list and on the map follow the visit order. No `NextStop` is sent for that moment.
- **Visible:** "Rerouting you…" in a white card in the middle of the faded map while the journey is
  routed again for longer than 0.5 s (PM, Monday "Rerouting calculation view", 2026-10-26).
- **Visible:** inside a gallery of a personalised journey the follow zooms to
  `GadSettings.galleryFollowZoom` (20); a camera the visitor moved stays.
- **Visible:** the position dot disappears when the position is lost, and in a privacy zone, instead of
  turning grey (PM, Monday "Privacy zone to have the same position marker as position disappearing",
  2026-10-26).
- **Visible:** the stop card keeps its instruction line while the route is routed again, so the card
  and the buttons above it do not move (RPR, Monday "Accessible routing", 2026-10-28).
- **Visible:** a floor without positioning shows no route, with the destination highlighted. "Resume
  your Journey" there starts the way back: the map stays on that floor, the lift or stairs to the next
  stop's floor nearest the destination is highlighted, and the card reads "NEXT STOP IS LEVEL n" /
  "Head to Level n by lift or stairs". The first new fix ends it (PM, Monday "POIs on floors with no
  positioning", 2026-10-26).
- **Visible:** gallery first (personalised journeys): the artworks' numbers are hidden while the leg
  ends at a gallery, and while the visitor is outside every gallery and the active artwork is in one;
  a detour keeps its marker. The POI of the gallery the visitor is in is not drawn (PM, Monday
  "Navigation to a gallery", 2026-10-26 and 2026-10-27).

#### Changed (SDK 6.0.0-beta.34, Map 6.0.0-beta.31; iOS 1.15.0, 1.16.0, 1.20.0)

- **BREAKING:** the library depends on SDK `6.0.0-beta.34` (was `6.0.0-beta.31`) and Map
  `6.0.0-beta.31` (was `6.0.0-beta.27`). A host app that declares them itself moves to the same
  versions. MIGRATION.md section 1 lists the SDK and Map changes; the host app of `ProximiMapView`
  installs the cached route network when it starts offline (SDK 6.0.0-beta.33).
- **Visible:** `ProximiMap.start()` works without a connection on a phone where the API accepted the
  token before. The route network then comes from the stored features.
- **Visible:** a token the API refused for at least two hours: the SDK deletes the venue data,
  `ProximiMap` closes its screens and `status` is `Failed` with `StartFailed`. The stored journey is
  kept. `start()` starts the same SDK again once the API accepts the token.
- **Visible:** the maps draw the illustrated map of the GAD style (its raster tiles) under the floor
  plan (Map 6.0.0-beta.29; PM, Monday "Illustrated map not visible").
- **Visible:** places, floors and geofences deleted on the server leave the phone within about 15
  minutes (SDK 6.0.0-beta.34).
- `GadSettings.minimumZoom` and `maximumZoom` go to `MapOptions`: the camera keeps to the range in
  every frame the map sets too, not only in the visitor's gestures.

#### Fixed

- **Visible:** in Arabic the counts of the Explore Museum lists ("5 spaces", "3 levels", the works
  and the amenities) are in Arabic. Before, they were in English: the import of the iOS catalog wrote
  them as plain strings beside the English plurals.
- `./gradlew check` passes: lint found two `remember` calls that return `Unit` and two `StateFlow`
  reads in composition. No change for the host app.

### [1.15.0] — 2026-10-28

The behaviour of the iOS package 1.14.0, which includes 1.13.0 and 1.13.1, and `ProximiExploreView` of
iOS 1.15.0, on SDK 6.0.0-beta.31 and Map 6.0.0-beta.27: the Explore Museum map legend and data icons,
the Journey Complete option, zoom limits, the position dot drawn on each fix, the map spinner, no
"Undo skip", the Android glass with the system's pressed state, and the Explore Museum map as a UI
component on the host app's SDK. The other changes of iOS 1.15.0 (the prompt options, `destination`,
SDK 6.0.0-beta.33 and Map 6.0.0-beta.30) come in a later release. There is no Android 1.13 or 1.14.

#### Added (iOS 1.15.0)

- `ProximiExploreView(sdk, modifier, settings)`: the Explore Museum map as a UI component on the host
  app's SDK, without title, Back and background (Nagarro, 2026-10-28: their app uses
  `ProximiMapView`, not `ProximiMap`). See "Explore Museum". `open(Screen.MUSEUM_MAP)` is unchanged.

#### Added (iOS 1.13.0, 1.13.1)

- `ProximiMapView.Option.JOURNEY_COMPLETE`: Journey Complete when the last stop is done. `ALL`
  includes it (Nagarro, Monday "Journey finished view", 2026-10-26).
- `GadSettings.minimumZoom` and `maximumZoom` (default `null`): zoom limits for every map of the
  library.
- `GadSettings.smoothsPosition` (default `false`), `notifiesGeofenceEvents` (default `true`) and
  `mapLoadingTimeout` (default 20 s). See "Settings".
- **Visible:** the Explore Museum map legend. The button left of the tabs opens it (PM, Monday
  "Explore Museum: Map legend missing"). The lists, the cards and the legend use the amenity icons of
  the venue data, the icons the map draws (PM, Monday "Explore Museum: thematic gallery icon"). See
  "Explore Museum".

#### Changed (iOS 1.13.0, 1.13.1)

- **BREAKING:** `ProximiMapView.Option.PROMPTS` no longer shows Journey Complete. A host app that
  passes `PROMPTS` without `ALL` adds `JOURNEY_COMPLETE` to keep it. `ProximiMap` is unchanged.
- **BREAKING:** the position dot is drawn on each fix, not slid toward it (PM, Monday "Visual delay",
  2026-10-23). `smoothsPosition = true` restores the sliding.
- **Visible:** the journey map, the Explore Museum map and the museum map show a spinner in the middle
  until the venue's features are on the map, the map reports an error or `mapLoadingTimeout` passes.
  The journey map also keeps it while the first leg is routed; the stop card shows an arrow there
  (PM, Monday "Spinner on map loading center", 2026-10-26).

#### Changed (iOS 1.14.0)

- **Visible:** the library's glass is the Android glass of the Figma "Glass review: iOS 26, iOS 27
  and Android" (node 16349:60996, 2026-10-24; Nagarro and RPR, 2026-10-26: the app leans on native
  Android components): a frosted surface, white 60 % over the blurred backdrop, with a light shadow,
  without the earlier gradient and white edge. Every `GlassStrength` draws it.
- **Visible:** the buttons are glass: `GhostButton` and the "Go here" and "Close" buttons of the map
  cards are no longer outlined. A selected button or chip has a 1 dp ink outline; a disabled one is
  at 40 %. The system ripple draws the pressed state; the buttons no longer shrink.
- **Visible:** the Map / List toggle of the journey map is the Material 3 segmented button.
- **Visible:** the Explore Museum lists fade out above the tab bar, so the rows do not show through
  its glass.

#### Removed (iOS 1.13.0)

- **Visible:** "Undo skip" in the journey list (PM, Monday "Undo skip removed", 2026-10-23).
  `ProximiMapEvent.SkipUndone` is deprecated and not sent.

#### Fixed

- **Visible:** an Explore Museum list opened after another starts at its top. Before, it kept the
  scroll position of the other list.

### [1.12.0] — 2026-10-24

The behaviour of the iOS package 1.12.0, which includes 1.10.0, 1.11.0, 1.11.1 and 1.11.2, on SDK
6.0.0-beta.31 and Map 6.0.0-beta.27 (as iOS SDK 6.0.0-beta.64 and Map 6.0.0-beta.51): privacy zones,
the Explore Museum map, maps that fade out at their edges, and curated tours that ignore the
artworks further on. There is no Android 1.10 or 1.11.

#### Changed (SDK 6.0.0-beta.31, Map 6.0.0-beta.27)

- **BREAKING:** the library depends on SDK `6.0.0-beta.31` (was `6.0.0-beta.30`) and Map
  `6.0.0-beta.27` (was `6.0.0-beta.26`). The Map requires SDK `6.0.0-beta.31`. A host app that
  declares these artifacts itself moves them to the same versions. The SDK adds
  `BlueiotBindingSignal.PrivacyZone(since)` and `ProximiioDiagnostics.NoPositionReason.PRIVACY_ZONE`,
  the Map `PositionStage.PRIVACY_ZONE`: a `when` over them without `else` adds the case.
- **BREAKING:** new `TrackingHealth.PRIVACY_ZONE`. A `when` over `TrackingHealth` without `else`
  adds the case.
- **Visible:** the binding reads relay-api's `private` (a band silent in a privacy zone) as
  `BlueiotBindingSignal.PrivacyZone(since)`; SDK 6.0.0-beta.30 read it as `Ok`.
- **Visible:** while the SDK is private, the map holds the dot grey at the last fix before the zone,
  and the navigator takes no fix: no off-route, re-route, deviation, passed stop or arrival. In
  `ProximiMapView` this also happens with the SDK option off.

#### Changed (Explore Museum)

- **Visible:** `open(Screen.MUSEUM_MAP)` shows the Explore Museum map of the PM's screens
  (2026-10-23), as iOS 1.11.0 (see "Explore Museum"): "Explore Museum" with the back button,
  "Level N" and a new floor changer, the galleries in the level colour, the Spaces, Highlights and
  Amenities lists and a card for a picked place. It has no position dot, no route, no search and no
  gallery legend. No host app change.
- **Visible:** the journey map and the Explore Museum map fade out over about 28 dp at every edge,
  so the background behind the view shows through (Figma map card; PM 2026-10-23), as iOS 1.11.1.
  The cards and buttons over the map do not fade. Touches on the faded edge still reach the map. No
  host app change. iOS 1.11.1 also centres the spinner of `ProximiMapView` without `BACKGROUND`;
  on Android it was already in the middle.

#### Changed (curated tours)

- **Visible:** a curated tour ignores the artworks further on (PM, Monday "Curated tours: ignore other
  artworks", 2026-10-23), as iOS 1.12.0. Only the next artwork counts as visited; an artwork the
  visitor passes or stays at out of order stays open, and no `ArtworkVisitedOutOfOrder` is sent.
  Personalised journeys are unchanged. Applies to `ProximiMap` and to `ProximiMapView` with
  `PROGRESS`. No host app change.

#### Fixed (Explore Museum)

- The Explore Museum map shows its floor changer once the museum's levels are loaded, as iOS 1.11.2.
  Before, a tap right after the map opened showed an empty menu.

#### Added

- Privacy zones (see "Privacy zones"): `ProximiMap` turns on `withholdsPositionsInPrivacyZones` and
  passes the wristband binding's `private` signal to the SDK (`setPositioningSourcePrivate`). A host
  app with `ProximiMapView` does both on its own SDK.
- **Visible:** `TrackingChanged(PRIVACY_ZONE)` while the visitor is in a privacy zone, instead of
  `LOST_PLACE` or `STOPPED_RESPONDING`.
- Field log: "wristband privacy: …", "privacy zone: private … · zone … · relay …", "privacy zone
  since …" on the binding session line, and "active, <link>, privacy zone" on the state line.

### [1.9.0] — 2026-10-23

The behaviour of the iOS package 1.9.0, which includes 1.8.0, on SDK 6.0.0-beta.30 and Map
6.0.0-beta.26 (as iOS SDK 6.0.0-beta.63 and Map 6.0.0-beta.50). There is no Android 1.8.0.
[MIGRATION.md](MIGRATION.md) lists the SDK and Map changes since SDK 6.0.0-beta.23 and Map
6.0.0-beta.21.

#### Added

- The library is on Maven: `io.proximi.gadkit:proximiio-gadkit` on
  `https://maven.eu.proximi.fi/releases/`, the repository of the SDK and the Map, from version
  1.9.0. A host app no longer needs the git submodule. It adds `includeGroup("io.proximi.gadkit")`
  to that repository and a version to the dependency (see "Adding the library"). The submodule with
  `includeBuild` still works.
- `GadSettings.geofenceExitDelay` (iOS 1.9.0): the time the position stays outside an entered
  geofence or privacy zone before the SDK reports the exit, 5 s by default. `ProximiMap` sets it in
  the SDK it starts. `ProximiMapView` runs on the host app's SDK, which has its own
  `ProximiioConfiguration.geofenceExitDelay` (SDK default 3 s). See "Settings".

#### Changed (iOS 1.8.0)

- **Visible:** the screens follow `Proximiio.venueDataChanges()`. The library no longer downloads
  the venue's features every 300 s and when the app returns to the foreground. The SDK's audit sync
  brings the changes (`ProximiioConfiguration.syncInterval`, 300 s by default).
  `GadSettings.venueDataRefreshInterval` is deprecated and has no effect: a host app that sets it
  gets a deprecation warning and can remove it.
- **Visible:** after a change of features the open journey routes its legs again
  (`JourneyNavigator.routeNetworkChanged()`). A stop whose POI changed takes the new title,
  position, floor and gallery on the open screen (`replace`), not only when the screen closes.
- **Visible:** `ProximiMapView` hands every change of `journey` to the navigator's `update(to)`. A
  stop the host app removes leaves the open screen; the screen no longer starts again and
  `onNavigator` is not called again. The navigator keeps a detour and the steps it has already
  made.

#### Changed (SDK 6.0.0-beta.30, Map 6.0.0-beta.26)

- **BREAKING:** the library depends on SDK `6.0.0-beta.30` (was `6.0.0-beta.28`) and Map
  `6.0.0-beta.26` (was `6.0.0-beta.25`). The Map requires SDK `6.0.0-beta.30`. A host app that
  declares these artifacts itself moves them to the same versions. No source change.
- **Visible:** an entered geofence exits only after the position has stayed outside it for a
  delay (SDK 6.0.0-beta.29). A return within the delay sends no exit and no new enter. The enter
  still arrives at once.
  - `ProximiMap`: 5 s (`GadSettings.geofenceExitDelay`). `ProximiMapView`: the host app's SDK, 3 s
    by default. `ProximiioConfiguration(geofenceExitDelay = 0.0)` gives the previous behaviour.
  - `ProximiMapEvent.GeofenceExited` arrives up to the delay after the visitor leaves.
  - The visit screen shows the gallery name up to the delay longer.
  - With `GadSettings.outOfOrderDwell` above 0, the time in another artwork's geofence counts up to
    the delay longer, so a shorter crossing marks that artwork visited out of order. The default of
    0 does not change.
- **Visible:** SDK 6.0.0-beta.30 changes, as iOS SDK 6.0.0-beta.63. No host app change.
  - The route network leaves out paths, level changers and obstacles with a position that is not
    in degrees, for example in EPSG:3857 metres. `features()` and the map still get them.
  - A level that is not a finite number, or one too large for a floor key, is on no floor and has
    no route.
  - A list page the API cut short no longer loses rows.
  - `journeys()` lists every journey, not only the 100 most recently edited.

### [1.7.0] — 2026-10-22

The behaviour of the iOS package 1.7.0 on newer SDK and Map versions (see
"Differences from the iOS package"). It is the first release since 0.14.0:
the 1.0.0 entries below were never tagged. One entry point, `ProximiMap`, replaces the screens and
services of 0.14.0 (Nagarro, 2026-10-16: "a single exposed wrapper component that encapsulates all
internal UI components and logic"). `ProximiMapView` is the journey map alone on the host app's SDK.
From 1.0.0 a minor or patch version does not break the public API.

#### Added (iOS 1.7.0)

- `ProximiMapView`'s `onNavigator`: the `JourneyNavigator` that draws the journey. The host app
  collects its `events` and sets `onCue` itself. See "The navigator".
- `ProximiJourney.ofJourney(journey, kind)`: a `ProximiJourney` from a map library `Journey`.
- [MIGRATION.md](MIGRATION.md): moving from `ProximiioMap` and an own `JourneyNavigator` on SDK
  6.0.0-beta.23 and Map 6.0.0-beta.21 to `ProximiMapView`.

#### Changed (SDK 6.0.0-beta.28, Map 6.0.0-beta.25)

- **BREAKING:** the library depends on SDK `6.0.0-beta.28` (was `6.0.0-beta.25`) and Map
  `6.0.0-beta.25` (was `6.0.0-beta.21`). A host app that declares these artifacts itself moves them
  to the same versions. No source change. The first start moves the SDK's database to a new schema:
  a test install that goes back to an older SDK clears the app's data first.
- **Visible:** the SDK router connects a lift, staircase or escalator only to the floors in its
  `properties.levels` (iOS 1.4.0). Routes can change: the venue data gives every level changer its
  `levels` and a path to it on each of these floors. No change in the host app.
- **Visible:** a ride over several floors is one level change: one instruction and one floor-change
  marker, from the floor boarded to the floor left (iOS 1.4.0).
- **Visible:** after the relay-api refuses the refresh token, a wristband session the relay-api
  gives no verdict on ends with "session lost", and the visitor scans the wristband again (iOS
  1.4.0).
- **Visible:** the map background is clear, as on iOS (0.15.0): the screen behind the map shows
  until the style's first frame. The map draws into a `TextureView`.
- **Visible:** a map shown again centres on the visitor at the zoom that shows their whole place.
- **Visible:** the open map draws venue data changes at once. See "Venue data changes".
- **Visible:** the map no longer crashes when it loads the organisation's style over the cached one
  (`Calling getLayer when a newer style is loading`), for example on the first start after the
  installation.
- **Visible:** a venue feature with a position in metres, not in degrees, no longer ends the app
  when the map opens or centres on it. The map does not draw, frame or centre on such a feature, and
  logs its id under the logcat tag `ProximiioMap.features`. Every map from 6.0.0-beta.14 to
  6.0.0-beta.23 crashes on such a feature once the SDK has stored it.
- **Visible:** other bad venue data no longer ends the app either. A floor level that is not a
  number reads as a level that does not parse, in the SDK and in the map, and a feature with a
  number too large for a `Double` is stored without its properties. A sync or position pass that fails is logged and skipped. No
  change in the host app.
- **Visible:** `setRouteOptions` keeps the leg to a reached stop and a leg the options do not
  change; a changed amenity icon is drawn again.
- MapLibre requests to `apiBaseUrl` carry `X-Visitor-Id` and `X-Proximiio-SDK` too. See "Privacy".

No change in the host app's code. MIGRATION.md section 1 lists every change.

#### Added (iOS 1.1.0 to 1.6.0)

- `ProximiMapView(sdk, journey, onJourneyChange, modifier, options, settings, routeAccessibility,
  mode, onEvent, onClose)`: the journey map as a UI component on the host app's SDK, with
  `ProximiJourney` and `ProximiMapView.Option` (`BOTTOM_BAR`, `BACKGROUND`, `PROMPTS`, `PROGRESS`,
  `JOURNEY_NOTIFICATION`, `ALL`). See "ProximiMapView". (iOS 1.2.0, 1.3.0)
- `ProximiMapEvent.PositionUncertain(stop)` and `StopPlaceDeleted(stop)`. A `when` over
  `ProximiMapEvent` with `else` keeps compiling. (iOS 1.3.0)
- Venue data changes while the app runs: the features are downloaded every
  `GadSettings.venueDataRefreshInterval` (300 s) in the foreground and when the app returns to the
  foreground; routes, screens and the stored journey follow. See "Venue data changes". One download
  of `GET /v7/geo/features` is about 90 KB compressed. (iOS 1.1.0)
- **Visible:** gallery first in personalised journeys (PM, Monday "Navigation to a gallery"): a leg
  to an artwork in another gallery ends at the gallery's POI, with the gallery filled and a red label
  on the map; the first fix inside the gallery routes the leg to the artwork. See "Gallery first".
  No change in the host app. (iOS 1.5.0, 1.6.0)
- **Visible:** an amenity picked in the menu of the journey map or the museum map gets the red
  marker with its white icon. On the visitor's floor the map shows the visitor and the amenity; on
  another floor it shows the amenity's floor around the amenity. (iOS 1.5.0)
- **Visible:** "AR" and audio badges on the stop card. (iOS 1.5.0)
- `GadSettings.venueDataRefreshInterval` (300 s), `amenityFramingMinimumMeters` (40 m) and
  `galleryFirstMarginMeters` (50 m), at the end of the parameters. (iOS 1.1.0, 1.5.0, 1.6.0)
- `FieldLog.file`, `FieldLog.TIME_STAMP` and `FieldLog.share(context, file)`. (iOS 1.4.2)
- For the Proximi GAD app, behind `@ProximiGadAppApi`: `ProximiMap.sdk`,
  `wristbandBindingStates()` and `postNotification(tag, title, body, group)`. (iOS 1.4.1, 1.4.2)
- Debug extra `gad.hostedMap`. (iOS 1.2.0)

#### Changed (iOS 1.1.0 to 1.6.0)

- **Visible:** the library posts no "next stop nearby" notification. `ProximiMapEvent.StopNearby` is
  unchanged; a host app that wants the notification posts it for the event. (iOS 1.3.0)
- **Visible:** the arrival at an artwork and out of order are checked on every fix, and a geofence
  the SDK counts the visitor inside (`enteredGeofenceIds`) counts as inside. A journey opened inside
  the first artwork's geofence now marks the arrival. (iOS 1.6.0)
- **Visible:** the arrival, out of order, gallery first and "Resume your Journey" change the journey
  one after another. (iOS 1.6.0)
- **Visible:** a lift, staircase or escalator is drawn on the floors in its `properties.levels` only.
  Its own floor is used only when `levels` is missing, empty or unreadable. In the GAD data lift
  9f411e5a and stairs 488d5bd3 (level 0, `levels` [1, 2, 3, 4]) no longer show on level 0. (iOS
  1.4.0)
- **Visible:** the badges also read `metadata.augmented_reality` and `metadata.audio` with the value
  `"enabled"`. (iOS 1.5.0)
- **Visible:** the stop card names the gallery of the next artwork only while the leg ends at that
  gallery. The gallery comes from the artwork's `gallery_id`, not from the gallery outline that holds
  the artwork. The caption names the visitor's gallery ("GALLERY · …") in a curated tour too. (iOS
  1.5.0)
- **Visible:** the stored journey drops a stop whose POI was deleted and moves a stop whose POI
  moved. Journey ready → "Start the Journey" starts the stored journey. (iOS 1.1.0)
- Field log time stamps have the date in UTC: `2026-10-19T12:52:10.123Z` instead of the local
  `14:52:10.123`. The field log no longer writes the SDK's geofence events during a visit. (iOS 1.4.2)
- The library depends on `androidx.lifecycle:lifecycle-process` for the foreground downloads.

#### Changed (iOS 0.15.0)

- **BREAKING:** the library depends on the Proximi.io Android SDK `6.0.0-beta.25` (was
  `6.0.0-beta.24`); the Map stays `6.0.0-beta.21`. A host app that declares the SDK artifacts itself
  moves them to `6.0.0-beta.25`. The SDK's `BlueiotBindingError` has the cases `SwapBandsApart`,
  `SwapAtReception` and `SigningUnavailable(retryAfter)`: a `when` over it without `else` needs
  them. `BlueiotBindingPolicy` has a new parameter `swapNearMeters` after `bindReceptionRequired`:
  a positional `copy` or destructuring of it needs a change.
- **Visible:** every request to `apiBaseUrl` carries `X-Visitor-Id` and `X-Proximiio-SDK`. The host
  app's Data safety form declares the device identifier for App functionality as well as Analytics
  (see "Privacy").
- **Visible:** the wristband errors of a refused swap and of the relay-api's signing outage have
  their own text: "Hold your current wristband next to the new one and scan again.", "Your
  wristband isn't reachable. Please swap it at the reception desk." and "The museum’s server is
  busy. Try again in a moment." (`ProximiMapException.Wristband`, `message`).

#### Changed (iOS 1.0.0)

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

#### Added (iOS 1.0.0)

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

#### Fixed (iOS 1.0.0)

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
