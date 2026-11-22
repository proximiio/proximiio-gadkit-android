package io.proximi.gadkit.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.proximi.gadkit.designsystem.GadIcon
import io.proximi.sdk.core.model.ProximiioAmenity

/**
 * The glyphs of the amenity kinds from the venue data (PM, 2026-11-22: every amenity icon is edited
 * in the portal). An amenity kind ([AmenityKind]) is drawn with the icon of its amenity in the venue
 * data. The glyph bundled in the library is drawn when the data has no icon for the kind, when two
 * amenities of the kind have a place (the women's and the men's restroom), or when
 * `GadSettings.usesVenueAmenityIcons` is `false`.
 *
 * A data icon is a dark glyph on a transparent square (120 × 120 px in the GAD data). It takes the
 * place of the bundled 16 dp glyph: tinted in lists and chips, in the centre of the 30 dp marker on
 * the maps. iOS `VenueGlyphs` (1.68.0).
 */
internal object VenueGlyphs {
    /** A rectangle; plain numbers, so that the unit tests need no Android graphics. */
    data class Box(val left: Float, val top: Float, val width: Float, val height: Float)

    /** Compose state: a screen that draws a kind's glyph is drawn again when the glyphs change. */
    private var stored by mutableStateOf<Map<GadIcon, Bitmap>>(emptyMap())

    /** The data glyph of [icon], or `null` for the bundled one. */
    fun glyph(icon: GadIcon): Bitmap? = stored[icon]

    /** Sets the data glyphs. Returns `true` when a glyph was added, removed or replaced. */
    fun set(glyphs: Map<GadIcon, Bitmap>): Boolean {
        val current = stored
        val changed = glyphs.keys != current.keys || glyphs.any { (icon, image) -> current[icon]?.sameAs(image) != true }
        if (changed) stored = glyphs
        return changed
    }

    /**
     * The amenity whose icon each kind takes: the one amenity of the kind that is in [used] and has
     * an icon. A kind with two such amenities has none. Pure; covered by tests.
     */
    fun amenityIds(amenities: List<ProximiioAmenity>, used: Set<String>, withIcon: Set<String>): Map<AmenityKind, String> {
        val candidates = mutableMapOf<AmenityKind, MutableSet<String>>()
        for (amenity in amenities) {
            if (amenity.id !in used || amenity.id !in withIcon) continue
            val kind = AmenityKind.kind(amenity.id, amenity.title) ?: continue
            candidates.getOrPut(kind) { mutableSetOf() }.add(amenity.id)
        }
        return candidates.filterValues { it.size == 1 }.mapValues { it.value.first() }
    }

    /**
     * Takes the glyphs from [icons] (by amenity id) for the amenities that places or level changers
     * use ([used]). [enabled] is `GadSettings.usesVenueAmenityIcons`.
     */
    fun update(amenities: List<ProximiioAmenity>, used: Set<String>, icons: Map<String, Bitmap>, enabled: Boolean): Boolean {
        if (!enabled) return set(emptyMap())
        val glyphs = mutableMapOf<GadIcon, Bitmap>()
        for ((kind, id) in amenityIds(amenities, used, icons.keys)) icons[id]?.let { glyphs[kind.gadIcon] = it }
        return set(glyphs)
    }

    /**
     * Draws the design's map marker of [width] × [height] pixels with [glyph] in its centre: a white
     * circle with a `#EEEDED` border of 1/30 of the side, and the glyph, dark, in the centre square
     * of 16/30 of the side.
     */
    fun drawMarker(canvas: Canvas, glyph: Bitmap, width: Float, height: Float) {
        val unit = minOf(width, height) / 30f
        val oval = RectF(unit / 2, unit / 2, width - unit / 2, height - unit / 2)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawOval(oval, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = unit
        paint.color = MARKER_STROKE
        canvas.drawOval(oval, paint)
        val box = aspectFit(glyph.width.toFloat(), glyph.height.toFloat(), Box(7 * unit, 7 * unit, 16 * unit, 16 * unit))
        drawGlyph(canvas, glyph, box, MARKER_GLYPH)
    }

    /** The marker of [drawMarker] as a square bitmap of [side] pixels. */
    fun marker(glyph: Bitmap, side: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        drawMarker(Canvas(bitmap), glyph, side.toFloat(), side.toFloat())
        return bitmap
    }

    /** Draws [glyph] in [box], tinted with [color]. */
    fun drawGlyph(canvas: Canvas, glyph: Bitmap, box: Box, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(glyph, null, RectF(box.left, box.top, box.left + box.width, box.top + box.height), paint)
    }

    /** [width] × [height] scaled to fit [box], in its centre. Pure; covered by tests. */
    fun aspectFit(width: Float, height: Float, box: Box): Box {
        if (width <= 0f || height <= 0f) return box
        val factor = minOf(box.width / width, box.height / height)
        val fittedWidth = width * factor
        val fittedHeight = height * factor
        return Box(box.left + (box.width - fittedWidth) / 2, box.top + (box.height - fittedHeight) / 2, fittedWidth, fittedHeight)
    }

    // Plain numbers: the unit tests load this object without Android graphics.
    private const val MARKER_STROKE = 0xFFEEEDED.toInt()
    private const val MARKER_GLYPH = 0xFF2B2B2B.toInt()
}
