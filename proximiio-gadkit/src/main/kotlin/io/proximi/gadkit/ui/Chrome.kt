package io.proximi.gadkit.ui

import io.proximi.gadkit.ProximiGadAppApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.proximi.gadkit.R
import io.proximi.gadkit.designsystem.IconGlassButton
import io.proximi.gadkit.designsystem.Palette
import io.proximi.gadkit.designsystem.ProvideGlassBackdrop
import io.proximi.gadkit.designsystem.SmallButton

/** The glass back button of every pushed screen (iOS `chevron.backward` in `.iconGlass`). Mirrors in RTL. */
@Composable
@ProximiGadAppApi
public fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconGlassButton(onClick, modifier) {
        Icon(painterResource(R.drawable.ic_chevron_backward), stringResource(R.string.back), Modifier.size(14.dp), tint = Palette.ink)
    }
}

/**
 * A white sheet with 50 dp corners and the content centred, as iOS `overlaySheetStyle()`
 * (sign out, manage journey). The sheet is as tall as its content, so long texts wrap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@ProximiGadAppApi
public fun OverlaySheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    OverlaySheet(onDismiss, rememberModalBottomSheetState(skipPartiallyExpanded = true), content)
}

/** [OverlaySheet] with its [sheetState], so the caller can hide it before an action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OverlaySheet(onDismiss: () -> Unit, sheetState: SheetState, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 50.dp, topEnd = 50.dp),
        containerColor = Palette.white,
        contentColor = Palette.ink,
        dragHandle = null,
    ) {
        // The sheet is white and opaque: its glass has nothing to blur.
        ProvideGlassBackdrop(null) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 30.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

/**
 * A chip (Figma "Button Small") with the ink outline when [selected]. Works around
 * designsystem `SmallButton`, whose glass border is drawn over its selected outline; drop the
 * extra border once `SmallButton` draws the outline last.
 */
@Composable
internal fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SmallButton(text, onClick, modifier.semantics { this.selected = selected }, selected = selected)
}

/** Marks a title as a heading for TalkBack. [merge] reads a title of several texts as one. */
@ProximiGadAppApi
public fun Modifier.asHeading(merge: Boolean = false): Modifier = semantics(mergeDescendants = merge) { heading() }
