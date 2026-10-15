package io.proximi.gadkit.visit

import io.proximi.gadkit.map.AmenityKind
import io.proximi.gadkit.map.VenuePlace

/**
 * End of the journey (Figma "Stage 03", 04.03 "End of journey — shop/café/book tour"), as iOS
 * `BeforeYouGoSheet`: the places to visit on the way out.
 */
internal object BeforeYouGo {
    /** The first café, the first museum shop and the first kiosk of [places], when the venue data has them. */
    internal fun suggestions(places: List<VenuePlace>): List<VenuePlace> = buildList {
        for (kind in listOf(AmenityKind.CAFE, AmenityKind.MUSEUM_SHOP)) {
            places.firstOrNull(kind::matches)?.let(::add)
        }
        places.firstOrNull { it.title.contains("kiosk", ignoreCase = true) }?.let(::add)
    }
}
