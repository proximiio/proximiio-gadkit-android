# Moving to ProximiioGADKit for Android

This guide is for a host app on SDK 6.0.0-beta.23 and Map 6.0.0-beta.21 that shows its journey with
the map library's `ProximiioMap` and its own `JourneyNavigator`. These are the versions of the
Proximi Android integration guide of 2026-10-13. ProximiioGADKit for Android 1.15.0 uses SDK
6.0.0-beta.31 and Map 6.0.0-beta.27; the next release uses SDK 6.0.0-beta.34 and Map 6.0.0-beta.30.

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
| SDK 6.0.0-beta.32 | `ProximiioAmenity::title` without an expected type is ambiguous: `title(language)` sits beside the `title` property. | `val f = ProximiioAmenity::title` does not compile; give the reference a type. `sortedBy(ProximiioAmenity::title)` compiles. | **Check** |
| SDK 6.0.0-beta.33 | The constructors of `ProximiioDiagnostics.ConfigurationSummary` (`allowsOfflineStart`), `OfflinePackageFetchSummary` (`isDataOnly`, `carriesAcceptedToken`) and `OfflinePackageFetcher.fetch` (`acceptedTokenDigest`) gain trailing parameters. | Code that calls them is compiled again. | **Check** |
| SDK 6.0.0-beta.31, Map 6.0.0-beta.27 | New cases `BlueiotBindingSignal.PrivacyZone(since)`, `ProximiioDiagnostics.NoPositionReason.PRIVACY_ZONE` and `PositionStage.PRIVACY_ZONE`. | A `when` over these types without `else` does not compile. Add the case. | **Required** with an exhaustive `when` |

The SDK and Map versions have no other source break. `ProximiioConfiguration` gains the defaulted
`geofenceExitDelay`, declared last, in SDK 6.0.0-beta.29, and the defaulted
`withholdsPositionsInPrivacyZones` in SDK 6.0.0-beta.31; existing calls compile unchanged. Code that
builds a `ProximiioDiagnostics.ConfigurationSummary` or calls `PositionSceneBuilder.scene(…)` itself is
built again: both have a new trailing parameter.

### Behaviour changes

| Since | Change | In the app | Mark |
|---|---|---|---|
| SDK 6.0.0-beta.24 | Visitor reporting is on by default (`visitorReportingEnabled`). Each geofence enter and exit is sent at once, not in the batch every 60 s. Beacon and privacy-zone events are no longer uploaded; the app still gets them. | Opt out with `visitorReportingEnabled = false`, or declare it (see the Data safety row). | **Check** |
| SDK 6.0.0-beta.25 | Every SDK request to `apiBaseURL` carries `X-Visitor-Id` and `X-Proximiio-SDK`. proximiio-api limits the request rate per device by `X-Visitor-Id`. | A proxy in front of the on-premises API passes both headers through. | **Required** for the production install |
| SDK 6.0.0-beta.25 | The device identifier serves App functionality (the rate limit, the wristband binding) as well as Analytics. | The Play Console Data safety form declares "Device or other IDs" for App functionality and Analytics, not shared and not used for tracking. | **Required** before a Play Store release |
| Map 6.0.0-beta.22 | `setRouteOptions(options)` keeps the leg walked to a reached stop and a leg the options do not change, without `Rerouted`. Before `start()` it measures the legs again. | Code that waits for `Rerouted` after `setRouteOptions` gets it only for a leg that changed. | **Check** |
| Map 6.0.0-beta.22 | An amenity whose icon changed is drawn again with the new icon. | | Info |
| SDK 6.0.0-beta.26 | The router connects a lift, staircase or escalator only to the floors in its `properties.levels`. Routes can change. A floor that no level changer connects has no route from another floor: the route fails with `NoLevelTransition`. | The venue data gives every level changer its `levels` and a path to it on each of these floors. | **Required** in the venue data |
| SDK 6.0.0-beta.26 | A ride over several floors is one `LevelChange` and one level-change instruction, from the floor boarded to the floor left. The map draws one floor-change marker per ride. | Code that reads the number of floors from `levelChanges.size` reads the number of rides. | **Check** |
| SDK 6.0.0-beta.26 | After the relay-api refuses the refresh token, the SDK binds the stored wristband again only when `GET /v1/bindings/current` reports the session active. Without a verdict the session ends with `Ended(SessionLost)`, and the visitor scans the wristband again. | | Info |
| SDK 6.0.0-beta.27 | The first start moves the SDK's database to a new schema. An older SDK cannot open it. | A test install that goes back to SDK 6.0.0-beta.26 or older clears the app's data first. | **Check** |
| SDK 6.0.0-beta.27 | `features()` can return a part of the venue on a fresh install until a `loadRouteNetwork()` download is stored. `SyncEvent.entity` carries the canonical name (`Feature`, `Floor`, …). `mapStyle()` returns the stored style. | Call `loadRouteNetwork()` before reading `features()`. Code that compares `SyncEvent.entity` with an alias or a plural compares it with the canonical name. | **Check** |
| Map 6.0.0-beta.23 | The map background is clear (`MapOptions.backgroundColor` is `Color.TRANSPARENT`, was `null`). The app's view shows behind the map before the style's first frame, and the map draws into a `TextureView`. | `MapOptions(backgroundColor = null)` keeps the earlier look. | **Check** |
| Map 6.0.0-beta.23 | A map shown again after a fix centres on the visitor at the zoom that shows their whole place and follows at that zoom. Before, it zoomed to `recentreZoom`. | | Info |
| Map 6.0.0-beta.23 | The session follows `venueDataChanges()`: it redraws the features, amenity icons, floors, places, geofences and the stored style when the SDK's data changes. Camera, floor, route and journey stay. | Remove own `reloadVenueData()` calls for these changes. A running `JourneyNavigator` still needs `routeNetworkChanged()` after a `features` change. | **Check** |
| Map 6.0.0-beta.23 | MapLibre requests to the API host carry `X-Proximiio-SDK` and `X-Visitor-Id`, as the SDK's requests do. | See the headers row of SDK 6.0.0-beta.25. | Info |
| SDK 6.0.0-beta.28 | `loadRouteNetwork()` throws `ProximiioError.Underlying` (the cause in `underlyingError`) when the downloaded network cannot be built, not an `IllegalArgumentException`. Transport and decoding failures are still `ProximiioAPIError`. | Code that catches `IllegalArgumentException` from this call catches `ProximiioError.Underlying`. | **Check** |
| SDK 6.0.0-beta.28 | A sync pass, a position provider or a visitor reporter that throws no longer ends the app: the SDK logs it at `ERROR` in the `Lifecycle` category and goes on with the next event. | | Info |
| Map 6.0.0-beta.24 | An `onStyleLoaded` handler that throws a `RuntimeException` no longer ends the app: the map logs it under the tag `ProximiioMap.style` and loads the rest of the style. | Code that relied on the crash looks in the log. | **Check** |
| Map 6.0.0-beta.24 | The camera frames leave out coordinates outside ±90 / ±180 or not finite. | | Info |
| Map 6.0.0-beta.25 | A feature with a position outside ±90 / ±180 or not finite is not drawn, hit-tested, framed or centred on; the map logs its id under the tag `ProximiioMap.features`. `MapCanvasCamera.setCenter` ignores such a coordinate, and `ProximiioMapCanvas.point(coordinate)` returns `null` for it, instead of throwing. | | Info |
| SDK 6.0.0-beta.29 | A geofence or privacy-zone exit comes `geofenceExitDelay` (3 s by default) after the position left it. Enters come at once. A position back inside within the delay emits nothing. Until the exit, `enteredGeofenceIds()` lists the geofence and a privacy zone keeps position reporting suppressed. `stop()` emits every waiting exit. `GeofenceEvent.Exited.dwellTime` runs to the first position outside. | An app that acts on a `geofenceEvents()` exit, for example the end of an artwork's audio, acts 3 s later. `ProximiioConfiguration(geofenceExitDelay = 0.0)` restores the exit at the first position outside. | **Check** |
| SDK 6.0.0-beta.30 | `journeys()` returns every journey, 500 per request. Before, it returned the API's default page: the 100 most recently edited journeys. | An app that lists journeys gets all of them. | **Check** |
| SDK 6.0.0-beta.30 | A path, level changer or obstacle whose coordinates are not in degrees is left out of the route network. `features()` keeps it. A level that is not finite, or one beyond about ±2.1e7, is on no floor and has no route. | | Info |
| Map 6.0.0-beta.26 | Built against SDK 6.0.0-beta.30, which it requires. No map API change and nothing drawn changes. | Take SDK 6.0.0-beta.30 and Map 6.0.0-beta.26 together. | Info |
| SDK 6.0.0-beta.31 | The binding reads relay-api's `private` (a band silent in a privacy zone) as `BlueiotBindingSignal.PrivacyZone(since)`; older SDKs read it as `Ok`. With `withholdsPositionsInPrivacyZones` on, `positions()` emits no fix while private and positioning stays available. | Turn the option on and pass the binding's signal with `setPositioningSourcePrivate(since)` (README "Privacy zones"). | **Required** for the privacy zones |
| Map 6.0.0-beta.27 | While the SDK is private, the dot stays grey at the last fix before the zone, also with the SDK option off, and `JourneyNavigator` takes no fix: no off-route, re-route, deviation, passed stop or arrival. Built against SDK 6.0.0-beta.31, which it requires. | Take SDK 6.0.0-beta.31 and Map 6.0.0-beta.27 together. | Info |
| SDK 6.0.0-beta.32 | A permission counts as asked only when its prompt was shown. Without a launcher, after a launcher that threw or a cancelled request, it reads `NOT_DETERMINED`, not `DENIED`, and the next request asks again. `requestMotionPermission()` no longer throws when the launcher throws. | | **Check** |
| SDK 6.0.0-beta.32 | `refreshAmenities()` that changes only an amenity's metadata or title translations emits `VenueDataKind.amenities`. | | Info |
| SDK 6.0.0-beta.33 | `authenticate()` no longer throws without a connection on a phone where the API accepted the token before (`allowsOfflineStart`, on by default). `tokenStatus()` is then `UNVERIFIED`: positioning runs on the stored venue, nothing is synced or uploaded. `loadRouteNetwork()` still needs the network. | Install the route network with `loadCachedRouteNetwork()` while `tokenStatus()` is not `VERIFIED`, and as the fallback of a failed download. `allowsOfflineStart = false` keeps the old behaviour. | **Required** |
| SDK 6.0.0-beta.33 | Only a clear 401 counts as a refused token. After three refusals over at least two hours the SDK emits `SyncFailed("unauthorized")`, stops and deletes the venue data, the offline packages, the waiting uploads and the token; `tokenStatus()` is `REJECTED`. A token of another application deletes the stored data before the first sync. An unclear 401 is `ProximiioAPIError.Server(401)`. | Start the SDK again after a new `authenticate()`. | **Check** |
| SDK 6.0.0-beta.33 | `authenticate()` gives up after 30 s on a connection that does not answer. Waiting uploads survive an app kill (with consent, at most 500 of each kind). | | Info |
| SDK 6.0.0-beta.34 | Places, floors, departments, geofences, privacy zones and inputs deleted on the server leave the phone within about 15 minutes, as `SyncEvent.Deleted` and in `venueDataChanges()`. Map features the server deleted by their bare UUID leave the map. | | Info |
| Map 6.0.0-beta.28 | A map opened after the last fix shows the dot at once, at the fix's real age, and that fix frames the place and arms the follow. With a zoom range, every zoom the camera writes keeps to it. | | Info |
| Map 6.0.0-beta.29 | The style's raster tiles (`proximiio:raster:*` metadata) are drawn below the floor fills: the GAD illustrated map. A journey layer style set to `null` takes its layer off the map. A level two places share is named by the visitor's place. | `MapOptions(rasterTiles = false)` keeps the vector plan alone. | **Check** |
| Map 6.0.0-beta.30 | Requires SDK 6.0.0-beta.33. After the SDK refused the token for good, the session deletes the stored style and tiles, takes the venue off the map, shows `fallbackStyleUrl` or an empty style and ends a running `JourneyNavigator`. | After a new successful `authenticate()`, call `session.start()` and `navigator.start()` again. | **Check** |

### Added

All Info.

- SDK 6.0.0-beta.24: `ProximiioConfiguration.eventBatching`.
- SDK 6.0.0-beta.25: `Proximiio.apiVisitorID`, `Proximiio.apiSDKToken`,
  `BlueiotBindingPolicy.swapNearMeters`.
- Map 6.0.0-beta.22: `JourneyNavigator.remove(stopId)`, `replace(stop)`, `update(to)` and
  `routeNetworkChanged()`, `ProximiioMapSession.reloadVenueData()`, `MapOptions.backgroundColor`.
- SDK 6.0.0-beta.26: `LevelChanger.levels`.
- SDK 6.0.0-beta.27: `Proximiio.venueDataChanges()` with `VenueDataChange` and `VenueDataKind`,
  `refreshMapStyle()` and `cachedMapStyle()`. `VenueDataKind` is a class: a `when` over it needs an
  `else`. `loadRouteNetwork()` sends `If-None-Match` per page: an unchanged venue costs one small
  request per page.
- Map 6.0.0-beta.23: `VenueDataSource.venueDataChanges()`, `VenueStyleSource.storedStyleDocument()`
  and `VenueDataSource.apiVisitorID`, with defaults.
- SDK 6.0.0-beta.29: `ProximiioConfiguration.geofenceExitDelay` and
  `ProximiioConfiguration.DEFAULT_GEOFENCE_EXIT_DELAY`.
- SDK 6.0.0-beta.31: `ProximiioConfiguration.withholdsPositionsInPrivacyZones`,
  `Proximiio.setPositioningSourcePrivate(since)`, `privacyZoneStateChanges()`, `privacyZoneState()`
  and `PrivacyZoneState`.
- Map 6.0.0-beta.27: `PositionStyle.privacy` and `PositionPrivacyStyle`.
- SDK 6.0.0-beta.32: `ProximiioAmenity.metadata`, `title(language)`, `titleTranslations` and
  `Proximiio.requestBluetoothPermission()`.
- SDK 6.0.0-beta.33: `ProximiioConfiguration.allowsOfflineStart`, `Proximiio.tokenStatus()` with
  `TokenStatus`.
- Map 6.0.0-beta.28: `MapOptions.minimumZoom` and `maximumZoom`, `VenueDataSource.lastPosition()`.
- Map 6.0.0-beta.29: `MapOptions.rasterTiles`, `ProximiioMapSession.unavailableFontStacks`.

### Fixed

All Info.

- SDK 6.0.0-beta.24: the position dot no longer freezes during the visitor analytics upload.
- SDK 6.0.0-beta.25: on `503 signing_unavailable` a token refresh keeps the session and tries again
  after `retryAfter`.
- Map 6.0.0-beta.22: the map no longer crashes when it loads the organisation's style over the cached
  one (`Calling getLayer when a newer style is loading`).
- SDK 6.0.0-beta.26: a wristband session another phone took over no longer comes back by itself. A
  route from level 1 to level 4 no longer names level 3 or level 2.
- SDK 6.0.0-beta.27: a fresh install no longer shows most POIs without icon or title.
- Map 6.0.0-beta.23: a lift ride over several floors no longer draws a short zigzag next to the lift
  on the floor it arrives at. Use SDK 6.0.0-beta.26 or later only with Map 6.0.0-beta.23 or later.
- SDK 6.0.0-beta.28: a floor level that is not a number (`"NaN"`, `"Infinity"`, a number too large
  for a `Double`) on a path, level changer, obstacle or floor no longer ends the app; it reads as a
  level that does not parse. A feature with a number too large for a `Double` is stored without its
  properties, and the rest of the venue is stored.
- Map 6.0.0-beta.24: a venue feature with coordinates in metres no longer ends the app when the map
  opens (`latitude must be between -90 and 90`). Every map from 6.0.0-beta.14 to 6.0.0-beta.23
  crashes on such a feature once the SDK has stored it.
- Map 6.0.0-beta.25: a POI, place or journey stop in metres no longer ends the app when the map
  centres on it, and a feature with a floor level that is not a number no longer ends the app
  (`Cannot round NaN value.`).
- SDK 6.0.0-beta.30: a list page the API cut short no longer loses rows. A `NaN` or huge level no
  longer throws in the BlueIoT floor numbering.

The SDK and Map changelogs of these versions have the full entries.

SDK 6.0.0-beta.35 and later and Map 6.0.0-beta.31 and later are not used by the next ProximiioGADKit
for Android. A host app that declares them gets them instead of the versions above.

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

- Add `includeGroup("io.proximi.gadkit")` to the Proximi.io repository in `settings.gradle.kts` and
  depend on `io.proximi.gadkit:proximiio-gadkit:1.15.0` (README "Adding the library"). No git
  submodule is needed.
- The library brings SDK `6.0.0-beta.34` and Map `6.0.0-beta.30` as `api` dependencies. Remove the
  app's own SDK and Map dependencies, or set them to exactly these versions. Gradle takes the highest
  version requested, so a newer one in the app replaces the library's.
- The library needs `minSdk` 26, `compileSdk` 37 and Kotlin 2.2.

### Step 2: SDK and Map changes (Required / Check)

Section 1: `eventBatching` with positional arguments, the `BlueiotBindingError` and privacy-zone
cases (**Required**), the headers on the on-premises proxy, the Data safety form, the level changers'
`levels` in the venue data and the privacy zones (**Required**), visitor reporting, `swapNearMeters`, `setRouteOptions`,
`levelChanges`, the database schema, `features()`, the error of `loadRouteNetwork()`, the clear
background, `reloadVenueData()`, `onStyleLoaded`, the geofence exit delay and `journeys()`
(**Check**).

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
- The view calls it again with a new navigator when the screen starts again: a new `kind`, a
  journey without a stop on the map, or the first journey on an empty map. The previous navigator's
  `events` never completes: the app cancels its collection. Any other change of the journey goes to
  `update(to)` of the same navigator.
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
| Remove a stop | Remove it from `stops` |
| `detour(stop)`, `cancelDetour()` | No call. The visitor picks an amenity in the view's search and taps "Go here". `onEvent` reports `AmenitySelected`, `DetourStarted` and `DetourEnded`. |
| `resumeJourney()` | No call. The route follows a visitor who left it. |
| `setRouteOptions(options)` | The `routeAccessibility` parameter (`RouteAccessibility`) |

A swipe on a stop in the list reports `ProximiMapEvent.SkipRequested(stop)` on `onEvent` and changes
nothing. The app sets the stop to `SKIPPED`, or asks the visitor first. Without this the swipe has no
effect. `ProximiStop.id` is the POI id and `ProximiStop.artworkId` the collection id (`remote_id`); the
app matches the one its journey uses.

### Step 8: SDK streams (Info)

`sdk.geofenceEvents()` and `sdk.positions()` stay as they are. The view collects its own flows and
takes no events from the app's. The view reports no geofence event. It follows
`sdk.venueDataChanges()` itself: the open map draws changed venue data, and the journey routes again
after a change of features.

### Step 9: The view's navigator (Check)

The navigator from `onNavigator` has the view's rules, not the map library's defaults (README "The
navigator"):

| | The view's navigator |
|---|---|
| `StopSkipped`, `LegChanged`, `JourneyFinished` | Sent when the view applies the app's change of `journey` (`update(to)`). |
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
