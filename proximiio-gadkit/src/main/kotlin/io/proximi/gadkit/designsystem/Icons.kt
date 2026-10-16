package io.proximi.gadkit.designsystem

import io.proximi.gadkit.R
import androidx.annotation.DrawableRes

/** The design's icons (Figma "Icons", node 6902:47690), as in iOS `GADIcon`. */
internal enum class GadIcon(@param:DrawableRes val glyph: Int, @param:DrawableRes val marker: Int?) {
    Cafe(R.drawable.icon_cafe, R.drawable.icon_cafe_marker),
    Compass(R.drawable.icon_compass, null),
    Elevator(R.drawable.icon_elevator, R.drawable.icon_elevator_marker),
    Info(R.drawable.icon_info, R.drawable.icon_info_marker),
    MaleToilet(R.drawable.icon_male_toilet, R.drawable.icon_male_toilet_marker),
    FemaleToilet(R.drawable.icon_female_toilet, R.drawable.icon_female_toilet_marker),
    PrayerRoom(R.drawable.icon_prayer_room, R.drawable.icon_prayer_room_marker),
    Search(R.drawable.icon_search, null),
    Shop(R.drawable.icon_shop, R.drawable.icon_shop_marker),
    Stairs(R.drawable.icon_stairs, R.drawable.icon_stairs_marker),
}
