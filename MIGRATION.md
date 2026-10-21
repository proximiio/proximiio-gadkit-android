# Moving to ProximiioGADKit for Android

This guide is for a host app on SDK 6.0.0-beta.23 and Map 6.0.0-beta.21 that shows its journey with
the map library's `ProximiioMap` and its own `JourneyNavigator`. These are the versions of the
Proximi Android integration guide of 2026-10-13. ProximiioGADKit for Android 1.7.0 uses SDK
6.0.0-beta.25 and Map 6.0.0-beta.22.

Each change has a mark:

- **Required**: without the change the app does not build or does not work.
- **Check**: a change only if the app uses the named API.
- **Optional**: a feature the app can turn on.
- **Info**: no change in the app.

## 1. SDK and Map changes since SDK 6.0.0-beta.23 and Map 6.0.0-beta.21

### Source break

| Since | Change | In the app | Mark |
|---|---|---|---|
| SDK 6.0.0-beta.24 | `ProximiioConfiguration` has a new parameter `eventBatching` (default `false`) before `positioning`. | A call that passes `positioning` or a later argument by position, or destructures the configuration, passes it by name. Recompile in every case. | **Required** with positional arguments |
| SDK 6.0.0-beta.25 | `BlueiotBindingError` has three new cases (relay-api 0.11): `SwapBandsApart(requestID)` (code 18), `SwapAtReception(requestID)` (code 19) and `SigningUnavailable(retryAfter, requestID)` (code 20). Before, the first two arrived as `Unexpected` and the third as `Server(503)`. | A `when` over `BlueiotBindingError` without `else` does not compile. Add the three cases. The SDK's texts: "Hold your current wristband next to the new one and scan again.", "Your wristband isn't reachable. Please swap it at the reception desk." and, for `SigningUnavailable`, a retry after `retryAfter` seconds. | **Required** with an exhaustive `when` |
| SDK 6.0.0-beta.25 | `BlueiotBindingPolicy` has a new parameter `swapNearMeters` after `bindReceptionRequired`. | A positional `copy` or destructuring of the policy passes the arguments by name. Building one compiles. | **Check** |

The SDK and Map versions have no other source break.

### Behaviour changes

| Since | Change | In the app | Mark |
|---|---|---|---|
| SDK 6.0.0-beta.24 | Visitor reporting is on by default (`visitorReportingEnabled`). Each geofence enter and exit is sent at once, not in the batch every 60 s. Beacon and privacy-zone events are no longer uploaded; the app still gets them. | Opt out with `visitorReportingEnabled = false`, or declare it (see the Data safety row). | **Check** |
| SDK 6.0.0-beta.25 | Every SDK request to `apiBaseURL` carries `X-Visitor-Id` and `X-Proximiio-SDK`. proximiio-api limits the request rate per device by `X-Visitor-Id`. | A proxy in front of the on-premises API passes both headers through. | **Required** for the production install |
| SDK 6.0.0-beta.25 | The device identifier serves App functionality (the rate limit, the wristband binding) as well as Analytics. | The Play Console Data safety form declares "Device or other IDs" for App functionality and Analytics, not shared and not used for tracking. | **Required** before a Play Store release |
| Map 6.0.0-beta.22 | `setRouteOptions(options)` keeps the leg walked to a reached stop and a leg the options do not change, without `Rerouted`. Before `start()` it measures the legs again. | Code that waits for `Rerouted` after `setRouteOptions` gets it only for a leg that changed. | **Check** |
| Map 6.0.0-beta.22 | An amenity whose icon changed is drawn again with the new icon. | | Info |

### Added

All Info.

- SDK 6.0.0-beta.24: `ProximiioConfiguration.eventBatching`.
- SDK 6.0.0-beta.25: `Proximiio.apiVisitorID`, `Proximiio.apiSDKToken`,
  `BlueiotBindingPolicy.swapNearMeters`.
- Map 6.0.0-beta.22: `JourneyNavigator.remove(stopId)`, `replace(stop)`, `update(to)` and
  `routeNetworkChanged()`, `ProximiioMapSession.reloadVenueData()`, `MapOptions.backgroundColor`.

### Fixed

All Info.

- SDK 6.0.0-beta.24: the position dot no longer freezes during the visitor analytics upload.
- SDK 6.0.0-beta.25: on `503 signing_unavailable` a token refresh keeps the session and tries again
  after `retryAfter`.
- Map 6.0.0-beta.22: the map no longer crashes when it loads the organisation's style over the cached
  one (`Calling getLayer when a newer style is loading`).

The SDK and Map changelogs of these versions have the full entries.

SDK 6.0.0-beta.26 and later and Map 6.0.0-beta.23 and later are not used by ProximiioGADKit for
Android 1.7.0. A host app that declares them gets them instead of the versions above.

## 2. From ProximiioMap to ProximiMapView

| Step | | Mark |
|---|---|---|
| 1 | Dependencies | **Required** |
| 2 | The SDK and Map changes of section 1 | **Required** / **Check** |
| 3 | Languages | **Check** |
| 4 | The map screen | **Required** |
| 5 | The journey | **Required** |
| 6 | Navigator events and cues | **Required** |
| 7 | Changes of the journey | **Required** |
| 8 | SDK streams | Info |
| 9 | The view's navigator | **Check** |
| 10 | Options | **Optional** |

### Step 1: Dependencies (Required)

- Add the library as a git submodule at the tag `1.7.0`, include its build and depend on
  `io.proximi.gadkit:proximiio-gadkit` without a version (README "Adding the library").
- The library brings SDK `6.0.0-beta.25` and Map `6.0.0-beta.22` as `api` dependencies. Remove the
  app's own SDK and Map dependencies, or set them to exactly these versions. Gradle takes the highest
  version requested, so a newer one in the app replaces the library's.
- The library needs `minSdk` 26, `compileSdk` 37 and Kotlin 2.2. With the Gradle configuration cache
  on, set `kotlin.internal.collectFUSMetrics=false` in `gradle.properties`.

### Step 2: SDK and Map changes (Required / Check)

Section 1: `eventBatching` with positional arguments and the `BlueiotBindingError` cases
(**Required**), the headers on the on-premises proxy and the Data safety form (**Required**),
visitor reporting, `swapNearMeters` and `setRouteOptions` (**Check**).

### Step 3: Languages (Check)

The library ships English and Arabic strings and its fonts. If the app limits its languages
(`androidResources.localeFilters` or `resourceConfigurations`), it keeps `en` and `ar`, so the app's
language applies to the library's screen.

### Step 4: The map screen (Required)

`ProximiMapView` replaces the app's `ProximiioMapSession`, `ProximiioMap` and `JourneyNavigator`. It
draws the map, the route, the stops, the floors and the level changes, search with the amenity
shortcuts, and the list.

Before:

```kotlin
val session = remember { ProximiioMapSession(sdk, context) }
ProximiioMap(session = session)
LaunchedEffect(session) {
    val navigator = JourneyNavigator(session, journey, JourneyRules.VENUE_VISIT)
    navigator.onCue = { cue -> /* … */ }
    launch { navigator.events.collect { event -> /* … */ } }
    navigator.start()
}
```

After:

```kotlin
var journey by remember { mutableStateOf(ProximiJourney.ofArtworks(artworkIds)) }   // step 5; the app stores it

ProximiMapView(
    sdk = sdk,                                   // the started SDK, as before
    journey = journey,
    onJourneyChange = { journey = it },
    onEvent = { event ->
        if (event is ProximiMapEvent.SkipRequested) {   // step 7
            journey = journey.copy(stops = journey.stops.map {
                if (it.id == event.stop.id) it.copy(state = ProximiJourney.State.SKIPPED) else it
            })
        }
    },
    onNavigator = { navigator ->                 // step 6
        navigator.onCue = { cue -> /* … */ }
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            navigator.events.collect { event -> /* … */ }
        }
    },
)
```

- `sdk` is the app's started `Proximiio` with the wristband binding's position provider attached.
  `start()`, `attachPositionProvider` and `BlueiotWristbandBinding` stay as they are. The view does not
  start, stop or configure the SDK.
- The app no longer creates the map session or the navigator and no longer calls `start()`, `end()`,
  `cameraRules`, `guidanceRules` or `deviationRules` on them.

### Step 5: The journey (Required)

The view shows a `ProximiJourney`: a `kind` and stops in visit order, each with an `id` and a
`state` (`PENDING`, `VISITED`, `SKIPPED`). `toJson()` and `ProximiJourney.fromJson(text)` store it,
in the same JSON as iOS.

| The app has | `ProximiJourney` |
|---|---|
| A map library `Journey` | `ProximiJourney.ofJourney(journey, JourneyKind.PERSONALISED)`. Stop ids stay; `DONE` is `VISITED`, `SKIPPED` stays, other states are `PENDING`; detour stops are left out. |
| Artwork ids | `ProximiJourney.ofArtworks(listOf("GAD.2010.00033", "GAD.2018.00034"), JourneyKind.CURATED)`: collection ids (`remote_id`) or POI ids, all pending. |

- `CURATED` keeps the app's order and routes straight to each artwork.
- `PERSONALISED` routes to an artwork's gallery first (README "Gallery first").

### Step 6: Navigator events and cues (Required)

The app's collection of `navigator.events` and its `onCue` move into `onNavigator` (step 4). The code
inside them stays.

- The view calls `onNavigator` before the journey starts. A collection started inside the call with
  `CoroutineStart.UNDISPATCHED` gets every later event.
- The view calls it again with a new navigator when the screen starts again: a removed stop, a new
  `kind`, or the first journey on an empty map. The previous navigator's `events` never completes:
  the app cancels its collection.
- A recreated activity keeps the navigator and does not call `onNavigator` again. Collect in a scope
  that outlives the activity, for example `viewModelScope`.
- The view sets neither `onCue` nor `cueRules`.

### Step 7: Changes of the journey (Required)

The app changes the journey and passes it as `journey`. The view applies each change to the open
screen. It does not see calls on the navigator that change the journey, and its next change can undo
them.

| Before, on the app's navigator | After |
|---|---|
| `markDone(stopId)` | The stop's state `VISITED` |
| `skip(stopId)`, `skip()` | The stop's state `SKIPPED` |
| `reopen(stopId)` | The stop's state `PENDING` |
| `add(stop)`, `insert(stop, index)` | Add or insert `ProximiJourney.Stop(id)` |
| `move(stopId, toIndex)`, `apply(proposal)` | Reorder `stops` |
| Remove a stop | Remove it from `stops`. The screen starts again (step 6). |
| `detour(stop)`, `cancelDetour()` | No call. The visitor picks an amenity in the view's search and taps "Go here". `onEvent` reports `AmenitySelected`, `DetourStarted` and `DetourEnded`. |
| `resumeJourney()` | No call. The route follows a visitor who left it. |
| `setRouteOptions(options)` | The `routeAccessibility` parameter (`RouteAccessibility`) |

A swipe on a stop in the list reports `ProximiMapEvent.SkipRequested(stop)` on `onEvent` and changes
nothing. The app sets the stop to `SKIPPED`, or asks the visitor first. Without this the swipe has no
effect. `ProximiStop.id` is the POI id and `ProximiStop.artworkId` the collection id (`remote_id`); the
app matches the one its journey uses.

### Step 8: SDK streams (Info)

`sdk.geofenceEvents()` and `sdk.positions()` stay as they are. The view collects its own flows and
takes no events from the app's. The view reports no geofence event.

### Step 9: The view's navigator (Check)

The navigator from `onNavigator` has the view's rules, not the map library's defaults (README "The
navigator"):

| | The view's navigator |
|---|---|
| `StopSkipped`, `LegChanged`, `JourneyFinished` | Sent when the view applies the app's change of `journey`. |
| `FarFromRoute`, `OffRouteTooLong` | After `GadSettings.offRouteMeters` (30 m) and `GadSettings.offRouteAfter` (300 s). |
| `StopReached` and the cue `ARRIVED` | Only for a detour to an amenity. A leg to an artwork never reports an arrival. The visitor reaches an artwork by entering its geofence, which the app's `geofenceEvents()` reports. |
| The cue `APPROACHING_DESTINATION` | 15 m before the end of the leg. In a personalised journey a leg to an artwork in another gallery ends at that gallery; other legs end at the artwork. |

Every time and distance of the view is in `GadSettings` (README "Settings"), passed as `settings`.

### Step 10: Options (Optional)

`options` is empty by default: the view draws the journey and reports events.

| `ProximiMapView.Option` | Adds |
|---|---|
| `BOTTOM_BAR` | A bar with Back (with `onClose`) and a Map / List button. |
| `BACKGROUND` | The library's background image around the map card. |
| `PROMPTS` | The view's own prompts: skip, off the route, uncertain position, change of route, leave the tour, Journey Complete. |
| `PROGRESS` | The view marks artworks visited, orders a personalised journey and reports the journey through `onJourneyChange`. |
| `JOURNEY_NOTIFICATION` | The ongoing journey notification with the next instruction (iOS `.liveActivity`). |
