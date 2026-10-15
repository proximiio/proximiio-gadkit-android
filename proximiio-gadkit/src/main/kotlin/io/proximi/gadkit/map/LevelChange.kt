package io.proximi.gadkit.map

import android.graphics.ImageDecoder
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.ImageView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.proximi.gadkit.FieldLog
import io.proximi.gadkit.R
import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.visit.LevelChangeCard
import io.proximi.gadkit.visit.LevelChangeDetector
import io.proximi.gadkit.visit.LiftArea
import io.proximi.gadkit.visit.LiftDetector
import io.proximi.gadkit.designsystem.GadTextStyle
import io.proximi.gadkit.designsystem.Palette
import io.proximi.gadkit.VenueService
import io.proximi.gadkit.map.lifts
import io.proximi.map.core.RouteManoeuvre
import io.proximi.map.live.ProximiioMapSession
import kotlinx.coroutines.delay

/**
 * The level change animation (iOS `LevelChange.swift`, the design GIFs in
 * `guggenheim-ios/design/level-changing`): a floor plate splits into two, then an up or down arrow
 * with "Level up" or "Level down". The journey screen and the museum map show it over the map, as
 * iOS `levelChangeAnimation(for:venue:)`:
 *
 * - while the visitor rides a lift ([LiftDetector]: positions stay inside a lift outline for 3 s).
 *   With a route level change at the lift, the animation towards the route's floor. Without one,
 *   the first frame and "In the lift" until the floor changes. Leaving the lift without a floor
 *   change closes it.
 * - when the position changes floor ([LevelChangeDetector]): the animation towards the new floor and
 *   "Level X", on the same fix as the map's floor switch, so the map moves to the visitor's floor
 *   under it. It closes after 2.8 s.
 *
 * A tap closes the overlay. The animations are `res/raw/level_{up,down}_{en,ar}.webp`, converted
 * from the iOS APNGs by `scripts/convert_level_change.sh`, and tinted with the position colour.
 * Arabic plays the `ar` animation, every other language the `en` one. With animations off (animator
 * duration scale 0), and on API 26–27, which cannot decode animated WebP, the last frame shows
 * without animation.
 */
@Composable
internal fun LevelChangeAnimation(session: ProximiioMapSession, venue: VenueService) {
    val floors = remember(session) { LevelChangeDetector() }
    val lift = remember(session) {
        val settings = venue.settings
        LiftDetector(settings.liftRideDwell.inWholeMilliseconds, settings.liftExitFixes)
    }
    var lifts by remember { mutableStateOf(emptyList<LiftArea>()) }
    // The lift the last fix was in.
    var currentLift by remember { mutableStateOf<LiftArea?>(null) }
    var card by remember { mutableStateOf<LevelChangeCard?>(null) }
    var shown by remember { mutableStateOf<LevelChangeCard?>(null) }
    LaunchedEffect(venue) { lifts = venue.lifts() }
    LaunchedEffect(session) {
        session.position.collect { position ->
            if (position == null) return@collect
            val coordinate = Coordinate(position.coordinate.latitude, position.coordinate.longitude)
            val area = lifts.firstOrNull { it.contains(coordinate) }
            if (area != null) currentLift = area
            val event = lift.update(area != null, SystemClock.elapsedRealtime())
            val change = floors.update(position.floor?.level)
            if (change != null) {
                if (FieldLog.isEnabled) FieldLog.log("level change: L${change.from} → L${change.to}")
                card = LevelChangeCard(LevelChangeCard.Phase.ARRIVED, change.from, change.to).also { shown = it }
                session.followMyFloor()
                return@collect
            }
            when (event) {
                LiftDetector.Event.BOARDED -> {
                    val from = floors.level ?: return@collect
                    // The route's next level change, when it leaves this floor by the lift the visitor is in.
                    val levelChange = (session.guidance.value?.manoeuvre?.kind as? RouteManoeuvre.Kind.LevelChange)?.change
                    val to = levelChange?.let {
                        currentLift?.rideTarget(it.featureType, it.fromLevel, it.toLevel, Coordinate(it.coordinate.latitude, it.coordinate.longitude), from)
                    }
                    if (FieldLog.isEnabled) FieldLog.log("lift boarded on L$from, target ${to?.let { "L$it" } ?: "unknown"}")
                    card = LevelChangeCard(LevelChangeCard.Phase.RIDING, from, to).also { shown = it }
                }
                LiftDetector.Event.LEFT -> if (card?.phase == LevelChangeCard.Phase.RIDING) card = null
                null -> Unit
            }
        }
    }
    LaunchedEffect(card) {
        val current = card?.takeIf { it.phase == LevelChangeCard.Phase.ARRIVED } ?: return@LaunchedEffect
        delay(SHOWN_MILLIS)
        if (card == current) card = null
    }
    AnimatedVisibility(
        visible = card != null,
        enter = fadeIn(tween(FADE_MILLIS, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(FADE_MILLIS, easing = FastOutSlowInEasing)),
    ) {
        shown?.let { LevelChangeOverlay(it, onClose = { card = null }) }
    }
}

/**
 * Full-screen overlay: the animation and "Level X". It plays once and keeps the last frame. A ride
 * with an unknown target shows the first frame and "In the lift"; the animation plays when the floor
 * changes. For TalkBack the overlay is a button that closes it.
 */
@Composable
private fun LevelChangeOverlay(card: LevelChangeCard, onClose: () -> Unit) {
    val to = card.to
    val description = when {
        to == null -> stringResource(R.string.in_the_lift)
        card.phase == LevelChangeCard.Phase.ARRIVED -> stringResource(R.string.you_are_now_on_level_n, to)
        else -> stringResource(R.string.going_to_level_n, to)
    }
    val closeLabel = stringResource(R.string.close_the_level_change)
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.white.copy(alpha = 0.92f))
            .pointerInput(Unit) {
                // The overlay takes the touches meant for the map under it.
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .pointerInput(onClose) { detectTapGestures { onClose() } }
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = closeLabel) {
                    onClose()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val imageModifier = Modifier.size(280.dp, 194.dp)
            key(to) {
                if (to != null) LevelChangeImage(to > card.from, imageModifier) else FirstFrame(imageModifier)
            }
            Text(
                if (to != null) stringResource(R.string.level_n, to) else stringResource(R.string.in_the_lift),
                style = GadTextStyle.H1Bold.textStyle,
                color = Palette.ink,
            )
        }
    }
}

/** The first frame of the up animation, without animation. It has no text yet, so EN and AR share it. */
@Composable
private fun FirstFrame(modifier: Modifier) {
    Image(
        painterResource(R.drawable.level_up_first),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(Palette.position, BlendMode.SrcIn),
    )
}

@Composable
private fun LevelChangeImage(isUp: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val arabic = LocalConfiguration.current.locales[0]?.language == "ar"
    val plays = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    val animation = remember(isUp, arabic, plays, resources) {
        if (plays && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) decode(resources, animationResource(isUp, arabic)) else null
    }
    if (animation != null) {
        val tint = PorterDuffColorFilter(Palette.position.toArgb(), PorterDuff.Mode.SRC_IN)
        AndroidView(
            factory = { viewContext ->
                ImageView(viewContext).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    colorFilter = tint
                    setImageDrawable(animation)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && animation is AnimatedImageDrawable) {
                        animation.repeatCount = 0
                        animation.start()
                    }
                }
            },
            modifier = modifier,
        )
    } else {
        Image(
            painterResource(lastFrameResource(isUp, arabic)),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(Palette.position, BlendMode.SrcIn),
        )
    }
}

private fun decode(resources: android.content.res.Resources, id: Int): Drawable? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(resources, id)) }.getOrNull()
    } else {
        null
    }

private fun animationResource(isUp: Boolean, arabic: Boolean): Int = when {
    isUp && arabic -> R.raw.level_up_ar
    isUp -> R.raw.level_up_en
    arabic -> R.raw.level_down_ar
    else -> R.raw.level_down_en
}

private fun lastFrameResource(isUp: Boolean, arabic: Boolean): Int = when {
    isUp && arabic -> R.drawable.level_up_ar_last
    isUp -> R.drawable.level_up_en_last
    arabic -> R.drawable.level_down_ar_last
    else -> R.drawable.level_down_en_last
}

private const val SHOWN_MILLIS = 2_800L
private const val FADE_MILLIS = 150
