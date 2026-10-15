package io.proximi.gadkit.visit

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.proximi.gadkit.R
import io.proximi.gadkit.designsystem.GadTextStyle
import io.proximi.gadkit.designsystem.Palette
import io.proximi.gadkit.designsystem.PrimaryButton
import io.proximi.gadkit.designsystem.TextActionButton
import io.proximi.gadkit.designsystem.glassSurface
import io.proximi.gadkit.ui.OverlaySheet
import io.proximi.gadkit.ui.asHeading

/** Manage journey (02.11): edit the stops, craft a new journey, or delete the current one. */
@Composable
public fun ManageJourneySheet(
    title: String,
    summary: String?,
    onEdit: () -> Unit,
    onCraftNew: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    var isConfirmingDelete by remember { mutableStateOf(false) }
    OverlaySheet(onDismiss = onClose) {
        Text(title, Modifier.asHeading(), style = GadTextStyle.H1Bold.textStyle, textAlign = TextAlign.Center)
        summary?.let { Text(it, style = GadTextStyle.BodySmallRegular.textStyle, textAlign = TextAlign.Center) }
        Column(
            Modifier.padding(top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrimaryButton(stringResource(R.string.edit_stops), onClick = onEdit)
            PrimaryButton(stringResource(R.string.craft_a_new_journey), onClick = onCraftNew)
            AnimatedContent(isConfirmingDelete, label = "delete") { confirming ->
                if (confirming) {
                    // PrimaryButton draws ink text; the destructive red label needs its own content.
                    DestructiveButton(stringResource(R.string.delete_this_journey), onDelete)
                } else {
                    TextActionButton(stringResource(R.string.delete_journey), onClick = { isConfirmingDelete = true })
                }
            }
        }
        TextActionButton(stringResource(R.string.close), onClick = onClose)
    }
}

/** Primary-sized glass button with a red label (iOS `.primary` with `role: .destructive`). */
@Composable
private fun DestructiveButton(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .glassSurface(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = GadTextStyle.BodyMedium.textStyle, color = Palette.activeRed)
    }
}
