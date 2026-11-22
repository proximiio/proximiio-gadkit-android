package io.proximi.gadkit.designsystem

import io.proximi.gadkit.R
import androidx.annotation.DrawableRes

/** The design's icons (Figma "Icons", node 6902:47690), as in iOS `GADIcon`. */
internal enum class GadIcon(@param:DrawableRes val glyph: Int, @param:DrawableRes val marker: Int?) {
    Cafe(R.drawable.icon_cafe, R.drawable.icon_cafe_marker),
    Compass(R.drawable.icon_compass, null),
    Elevator(R.drawable.icon_elevator, R.drawable.icon_elevator_marker),
    Info(R.drawable.icon_info, R.drawable.icon_info_marker),
    PrayerRoom(R.drawable.icon_prayer_room, R.drawable.icon_prayer_room_marker),
    Restaurant(R.drawable.icon_restaurant, R.drawable.icon_restaurant_marker),
    Search(R.drawable.icon_search, null),
    Shop(R.drawable.icon_shop, R.drawable.icon_shop_marker),
    Stairs(R.drawable.icon_stairs, R.drawable.icon_stairs_marker),
    Theatre(R.drawable.icon_theatre, R.drawable.icon_theatre_marker),

    /** The women's and the men's restroom: one icon (PM, 2026-11-22). */
    Toilet(R.drawable.icon_toilet, R.drawable.icon_toilet_marker),
}
