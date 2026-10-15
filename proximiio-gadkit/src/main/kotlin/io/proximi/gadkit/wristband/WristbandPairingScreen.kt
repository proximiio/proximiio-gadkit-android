package io.proximi.gadkit.wristband

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.proximi.gadkit.R
import io.proximi.gadkit.UiText
import io.proximi.gadkit.wristband.WristbandPairing.Step
import io.proximi.gadkit.designsystem.AuroraBox
import io.proximi.gadkit.designsystem.GadTextStyle
import io.proximi.gadkit.designsystem.GuggenheimSansDisplay
import io.proximi.gadkit.designsystem.Palette
import io.proximi.gadkit.designsystem.PrimaryButton
import io.proximi.gadkit.designsystem.TextActionButton
import io.proximi.gadkit.ui.asHeading
import io.proximi.gadkit.ui.asString

/**
 * Screens of the "Connect your wristband" flow (Figma section "Wristband", node 9330:83108), as
 * iOS `WristbandPairingView`. Copy follows the design, except where iOS `design/COPY-ISSUES.md`
 * lists a deviation.
 *
 * [onFinish]: "Skip for now", "Back to Hub" and "Continue" all leave the flow. The system Back
 * returns from the scanner and the code entry to the intro, is ignored while the bind is in
 * flight, and otherwise leaves the flow through the caller's handler.
 */
@Composable
public fun WristbandPairingScreen(flow: WristbandPairingFlow, onFinish: () -> Unit) {
    val pairing = flow.pairing
    val state by pairing.state.collectAsStateWithLifecycle()
    flow.locationDialog?.let { LocationDialogHost(it) }

    when (state.step) {
        Step.Scanning, Step.ManualEntry -> BackHandler(onBack = pairing::cancelScanning)
        is Step.Looking -> BackHandler {}
        else -> Unit
    }

    when (val step = state.step) {
        Step.Intro -> Intro(pairing::startScanning, onFinish)
        Step.Scanning -> WristbandScanner(
            onScan = flow::submit,
            onCancel = pairing::cancelScanning,
            onEnterManually = pairing::enterCodeManually,
            onCameraDenied = pairing::cameraAccessDenied,
        )
        Step.ManualEntry -> CodeEntry(state.enteredCode, state.invalidCodeMessage, onSubmit = flow::submit, onCancel = pairing::cancelScanning)
        is Step.Looking -> Looking()
        is Step.Connected -> Connected(step.label, onFinish)
        is Step.Failed -> Failed(step.message, step.attempts, onRetry = flow::retry, onFinish)
        Step.CameraDenied -> CameraDenied(pairing::enterCodeManually, onFinish)
    }
}

// Screens

@Composable
private fun Intro(onScan: () -> Unit, onSkip: () -> Unit) {
    PairingScreen(
        icon = PairingIcon.QR,
        titleLight = stringResource(R.string.connect),
        titleBold = stringResource(R.string.your_wristband),
        content = { BodyText(stringResource(R.string.your_wristband_tracks_where_you_are_in_2)) },
        actions = {
            PrimaryButton(stringResource(R.string.scan_qr_code), onScan)
            TextActionButton(stringResource(R.string.skip_for_now), onSkip)
        },
    )
}

@Composable
private fun Looking() {
    PairingScreen(
        icon = PairingIcon.WAVES,
        titleLight = stringResource(R.string.looking_for),
        titleBold = stringResource(R.string.your_wristband),
        isPulsing = true,
        content = { BodyText(stringResource(R.string.this_usually_takes_a_few_seconds)) },
        actions = {},
    )
}

@Composable
private fun Connected(label: String, onContinue: () -> Unit) {
    PairingScreen(
        icon = PairingIcon.CHECK,
        titleLight = stringResource(R.string.wristband),
        titleBold = stringResource(R.string.connected),
        content = {
            BodyText(stringResource(R.string.keep_it_on_your_wrist_the_galleries))
            OutlinedRow(stringResource(R.string.wristband_x, label), stringResource(R.string.paired))
        },
        actions = { PrimaryButton(stringResource(R.string.continue_action), onContinue) },
    )
}

/**
 * [message] comes from `WristbandCopy` and names the reason. The repeated-failure variant adds the
 * design's referral to a host and drops "Try again".
 */
@Composable
private fun Failed(message: UiText, attempts: Int, onRetry: () -> Unit, onFinish: () -> Unit) {
    val isRepeated = attempts > 1
    PairingScreen(
        icon = PairingIcon.EXCLAMATION,
        titleLight = stringResource(R.string.we_couldn_t_connect),
        titleBold = stringResource(R.string.your_wristband),
        content = {
            BodyText(message.asString())
            if (isRepeated) BodyText(stringResource(R.string.please_find_a_host_that_can_help))
        },
        actions = {
            if (!isRepeated) PrimaryButton(stringResource(R.string.try_again), onRetry)
            TextActionButton(stringResource(R.string.back_to_hub), onFinish)
        },
    )
}

@Composable
private fun CameraDenied(onEnterManually: () -> Unit, onFinish: () -> Unit) {
    val context = LocalContext.current
    PairingScreen(
        icon = PairingIcon.EXCLAMATION,
        titleLight = stringResource(R.string.camera),
        titleBold = stringResource(R.string.permission_denied),
        content = { BodyText(stringResource(R.string.allow_camera_access_in_settings_to_scan)) },
        actions = {
            PrimaryButton(stringResource(R.string.go_to_settings), onClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                context.startActivity(intent)
            })
            TextActionButton(stringResource(R.string.enter_code_manually), onEnterManually)
            TextActionButton(stringResource(R.string.back_to_hub), onFinish)
        },
    )
}

/**
 * "Enter your wristband code", as iOS `WristbandCodeEntryView`. The design references this screen;
 * it is not drawn yet. The field opens with the last typed code and takes the keyboard focus.
 */
@Composable
private fun CodeEntry(enteredCode: String, invalidCodeMessage: UiText?, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var code by rememberSaveable { mutableStateOf(enteredCode) }
    val focus = remember { FocusRequester() }
    val canSubmit = code.isNotBlank()
    LaunchedEffect(Unit) { focus.requestFocus() }

    AuroraBox(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.enter_your_wristband_code), Modifier.asHeading(), style = GadTextStyle.H1Bold.textStyle, color = Palette.ink)
            Text(stringResource(R.string.the_code_is_printed_on_the_wristband), style = GadTextStyle.BodySmallRegular.textStyle, color = Palette.ink)
            val shape = RoundedCornerShape(12.dp)
            val placeholder = stringResource(R.string.wristband_code)
            BasicTextField(
                value = code,
                onValueChange = { code = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .semantics { contentDescription = placeholder }
                    .background(Palette.white, shape)
                    .border(1.dp, if (invalidCodeMessage == null) Palette.inputStroke else Palette.errorStroke, shape)
                    .padding(16.dp),
                textStyle = GadTextStyle.BodyMedium.textStyle.copy(color = Palette.ink),
                singleLine = true,
                cursorBrush = SolidColor(Palette.ink),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { if (canSubmit) onSubmit(code) }),
                decorationBox = { field ->
                    Box {
                        if (code.isEmpty()) {
                            Text(placeholder, style = GadTextStyle.BodyMedium.textStyle, color = Palette.ink.copy(alpha = 0.4f))
                        }
                        field()
                    }
                },
            )
            invalidCodeMessage?.let {
                Text(it.asString(), style = GadTextStyle.BodyExtraSmall.textStyle, color = Palette.errorLabel)
            }
            Spacer(Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.connect), onClick = { onSubmit(code) }, enabled = canSubmit)
            TextActionButton(stringResource(R.string.cancel), onCancel, Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

// Building blocks

private enum class PairingIcon { QR, WAVES, CHECK, EXCLAMATION }

/**
 * Layout shared by the pairing screens (Figma "Wristband", nodes 10874:96775 and 9330:85003): a
 * 180 dp icon with a soft red ring, the two-weight 32 sp title, Body Small text 322 dp wide, and
 * the actions near the bottom.
 */
@Composable
private fun PairingScreen(
    icon: PairingIcon,
    titleLight: String,
    titleBold: String,
    isPulsing: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
) {
    AuroraBox(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(110.dp))
            IconRing(icon, isPulsing)
            Column(
                Modifier.width(322.dp).padding(top = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(Modifier.asHeading(merge = true), horizontalAlignment = Alignment.CenterHorizontally) {
                    val title = GadTextStyle.H1Regular.textStyle.copy(fontFamily = GuggenheimSansDisplay, fontSize = 32.sp)
                    Text(titleLight, style = title, color = Palette.ink, textAlign = TextAlign.Center)
                    Text(titleBold, style = title.copy(fontWeight = FontWeight.Bold), color = Palette.ink, textAlign = TextAlign.Center)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
            }
            Spacer(Modifier.weight(1f))
            Column(
                Modifier.width(322.dp).padding(bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp),
                content = actions,
            )
        }
    }
}

@Composable
private fun BodyText(text: String) {
    Text(text, style = GadTextStyle.BodySmallRegular.textStyle, color = Palette.ink, textAlign = TextAlign.Center)
}

/** 180 dp mark: the glyph in a white circle inside a blurred red ring. "Looking" pulses the ring. */
@Composable
private fun IconRing(icon: PairingIcon, isPulsing: Boolean) {
    val scale = if (isPulsing) {
        val transition = rememberInfiniteTransition(label = "pulse")
        transition.animateFloat(1f, 1.12f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "scale").value
    } else {
        1f
    }
    Box(Modifier.size(180.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Box(Modifier.size(140.dp).scale(scale).blur(14.dp, BlurredEdgeTreatment.Unbounded).border(22.dp, Palette.red.copy(alpha = 0.28f), CircleShape))
        Box(Modifier.size(104.dp).blur(4.dp, BlurredEdgeTreatment.Unbounded).border(8.dp, Palette.red.copy(alpha = 0.18f), CircleShape))
        if (icon == PairingIcon.QR) {
            // Phone with a QR code, as drawn in the design.
            Box(
                Modifier.size(34.dp, 58.dp).border(2.dp, Palette.ink, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_wristband_qr), null, Modifier.padding(bottom = 10.dp).size(16.dp), tint = Palette.ink)
            }
        } else {
            Box(Modifier.size(60.dp).background(Palette.white.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
                val glyph = when (icon) {
                    PairingIcon.WAVES -> R.drawable.ic_wristband_waves
                    PairingIcon.CHECK -> R.drawable.ic_wristband_check
                    else -> R.drawable.ic_wristband_exclamation
                }
                Icon(painterResource(glyph), null, Modifier.size(24.dp), tint = Palette.ink)
            }
        }
    }
}

/**
 * "Wristband 7KQ4M2 · Paired" and "Last Seen · Gallery 2, 9:38" (Figma Card_Glass_2 outline
 * variant): 1.5 dp ink stroke, 12 dp corners, 20 dp padding.
 */
@Composable
internal fun OutlinedRow(title: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().border(1.5.dp, Palette.ink, RoundedCornerShape(12.dp)).padding(20.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = GadTextStyle.BodySmallBold.textStyle, color = Palette.ink)
        Spacer(Modifier.weight(1f))
        Text(value, style = GadTextStyle.BodySmallRegular.textStyle, color = Palette.ink)
    }
}
