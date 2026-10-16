package io.proximi.gadkit

import io.proximi.gadkit.visit.VisitMeta
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One presentation at a time, as iOS `ProximiMap.open(_:)` and `ProximiMapPresenter`: the next
 * one shows after the previous one has gone, or after 0.7 s without a dismissal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProximiMapPresenterTest {
    private val events = mutableListOf<ProximiMapEvent>()
    private var dismissals = 0

    private fun TestScope.presenter() = ProximiMapPresenter(this, { events += it }, onDismissed = { dismissals++ }, log = {})

    @Test
    fun theFirstScreenOpensAtOnce() = runTest {
        val presenter = presenter()
        presenter.replace { presenter.show(Presentation.MuseumMap) }
        assertEquals(Presentation.MuseumMap, presenter.presentation.value)
        assertEquals(ProximiMap.Screen.MUSEUM_MAP, presenter.screen.value)
        assertEquals(listOf<ProximiMapEvent>(ProximiMapEvent.Opened(ProximiMap.Screen.MUSEUM_MAP)), events)
    }

    @Test
    fun theNextScreenWaitsForTheDismissalOfThePrevious() = runTest {
        val presenter = presenter()
        presenter.show(Presentation.MuseumMap)
        presenter.replace { presenter.show(Presentation.PlanJourney) }
        assertNull(presenter.presentation.value)
        // The museum map is still going away.
        assertEquals(ProximiMap.Screen.MUSEUM_MAP, presenter.screen.value)
        presenter.dismissed(Presentation.MuseumMap.key)
        assertEquals(Presentation.PlanJourney, presenter.presentation.value)
        assertEquals(
            listOf(
                ProximiMapEvent.Opened(ProximiMap.Screen.MUSEUM_MAP),
                ProximiMapEvent.Closed(ProximiMap.Screen.MUSEUM_MAP),
                ProximiMapEvent.Opened(ProximiMap.Screen.PLAN_JOURNEY),
            ),
            events,
        )
        assertEquals(1, dismissals)
        // The fallback does not show it a second time.
        advanceTimeBy(ProximiMapPresenter.DISMISSAL_FALLBACK_MILLIS + 1)
        assertEquals(3, events.size)
    }

    @Test
    fun withoutADismissalTheNextScreenShowsAfterTheFallback() = runTest {
        val presenter = presenter()
        presenter.show(Presentation.MuseumMap)
        presenter.replace { presenter.show(Presentation.PlanJourney) }
        advanceTimeBy(ProximiMapPresenter.DISMISSAL_FALLBACK_MILLIS - 1)
        assertNull(presenter.presentation.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(Presentation.PlanJourney, presenter.presentation.value)
        assertTrue(ProximiMapEvent.Closed(ProximiMap.Screen.MUSEUM_MAP) in events)
        // A late dismissal of the museum map changes nothing.
        presenter.dismissed(Presentation.MuseumMap.key)
        assertEquals(ProximiMap.Screen.PLAN_JOURNEY, presenter.screen.value)
    }

    @Test
    fun aHostThatLeavesTheCompositionDoesNotCloseTheScreen() = runTest {
        val presenter = presenter()
        presenter.show(Presentation.MuseumMap)
        presenter.dismissed(Presentation.MuseumMap.key)
        assertEquals(Presentation.MuseumMap, presenter.presentation.value)
        assertEquals(listOf<ProximiMapEvent>(ProximiMapEvent.Opened(ProximiMap.Screen.MUSEUM_MAP)), events)
    }

    @Test
    fun startingTheJourneyKeepsTheSlot() = runTest {
        val presenter = presenter()
        val journey = io.proximi.gadkit.visit.Journey(emptyList())
        presenter.show(Presentation.JourneyReady(journey, VisitMeta(title = "Light & Perception")))
        presenter.swap(Presentation.Visit(journey, io.proximi.gadkit.visit.JourneyKind.PERSONALISED))
        assertEquals(ProximiMap.Screen.JOURNEY, presenter.screen.value)
        assertEquals(ProximiMapEvent.Opened(ProximiMap.Screen.JOURNEY), events.last())
        assertTrue(events.none { it is ProximiMapEvent.Closed })
    }

    @Test
    fun closeDropsTheNextScreen() = runTest {
        val presenter = presenter()
        presenter.show(Presentation.MuseumMap)
        presenter.replace { presenter.show(Presentation.PlanJourney) }
        presenter.close()
        presenter.dismissed(Presentation.MuseumMap.key)
        advanceTimeBy(ProximiMapPresenter.DISMISSAL_FALLBACK_MILLIS + 1)
        assertNull(presenter.presentation.value)
        assertNull(presenter.screen.value)
        assertEquals(ProximiMapEvent.Closed(ProximiMap.Screen.MUSEUM_MAP), events.last())
    }

    @Test
    fun hidingSendsClosedOnceTheScreenHasGone() = runTest {
        val presenter = presenter()
        presenter.show(Presentation.PlanJourney)
        presenter.hide()
        assertTrue(events.none { it is ProximiMapEvent.Closed })
        presenter.dismissed(Presentation.PlanJourney.key)
        assertEquals(ProximiMapEvent.Closed(ProximiMap.Screen.PLAN_JOURNEY), events.last())
        assertNull(presenter.screen.value)
    }
}
