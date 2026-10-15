package io.proximi.gadkit.wristband

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.proximi.gadkit.R
import io.proximi.gadkit.designsystem.GadTextStyle
import io.proximi.gadkit.designsystem.Palette
import io.proximi.gadkit.designsystem.PrimaryButton
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera scanner for the QR code on the back of the wristband, as iOS `WristbandScannerView`:
 * CameraX preview with ML Kit barcode scanning, QR only. Reports the first payload verbatim.
 * Requests the camera permission when it is not granted. A denial calls [onCameraDenied]. Without a
 * camera, the screen shows a message and manual entry only.
 */
@Composable
internal fun WristbandScanner(onScan: (String) -> Unit, onCancel: () -> Unit, onEnterManually: () -> Unit, onCameraDenied: () -> Unit) {
    val context = LocalContext.current
    val hasCamera = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var hasCameraAccess by remember { mutableStateOf(isCameraGranted(context)) }
    var isCameraFailed by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraAccess = granted
        if (!granted) onCameraDenied()
    }
    LaunchedEffect(Unit) {
        if (hasCamera && !hasCameraAccess) request.launch(Manifest.permission.CAMERA)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val isAvailable = hasCamera && !isCameraFailed
        if (isAvailable && hasCameraAccess) {
            QrCameraPreview(onScan = onScan, onFailure = { isCameraFailed = true })
        } else if (!isAvailable) {
            Text(
                stringResource(R.string.scanning_is_not_available_on_this_device),
                Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
                style = GadTextStyle.BodyMedium.textStyle,
                color = Palette.white,
                textAlign = TextAlign.Center,
            )
        }
        Column(
            Modifier.fillMaxSize().safeDrawingPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                stringResource(R.string.hold_the_qr_code_on_the_back),
                Modifier.padding(horizontal = 40.dp).padding(top = 48.dp),
                style = GadTextStyle.BodyMedium.textStyle,
                color = Palette.white,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(260.dp).border(1.dp, Palette.white.copy(alpha = 0.6f), RoundedCornerShape(24.dp)))
            Spacer(Modifier.weight(1f))
            WhiteTextAction(stringResource(R.string.enter_code_manually), onEnterManually)
            Box(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                PrimaryButton(stringResource(R.string.cancel), onCancel)
            }
        }
    }
}

/** iOS `.textAction` with a white foreground, over the camera: underlined, 44 dp touch height. */
@Composable
private fun WhiteTextAction(text: String, onClick: () -> Unit) {
    Box(
        Modifier.defaultMinSize(minHeight = 44.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = GadTextStyle.BodySmallRegular.textStyle.copy(textDecoration = TextDecoration.Underline), color = Palette.white)
    }
}

@Composable
private fun QrCameraPreview(onScan: (String) -> Unit, onFailure: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnScan by rememberUpdatedState(onScan)
    val currentOnFailure by rememberUpdatedState(onFailure)
    val previewView = remember(context) { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycleOwner) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        val hasReported = AtomicBoolean(false)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var isDisposed = false

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { image ->
                    analyze(image, scanner) { payload ->
                        if (hasReported.compareAndSet(false, true)) mainExecutor.execute { if (!isDisposed) currentOnScan(payload) }
                    }
                }
            }
        providerFuture.addListener({
            if (isDisposed) return@addListener
            try {
                val cameraProvider = providerFuture.get().also { provider = it }
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {
                currentOnFailure()
            }
        }, mainExecutor)

        onDispose {
            isDisposed = true
            provider?.unbindAll()
            analysis.clearAnalyzer()
            scanner.close()
            analysisExecutor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun analyze(image: ImageProxy, scanner: com.google.mlkit.vision.barcode.BarcodeScanner, onPayload: (String) -> Unit) {
    val media = image.image
    if (media == null) {
        image.close()
        return
    }
    scanner.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
        .addOnSuccessListener { barcodes -> barcodes.firstNotNullOfOrNull { it.rawValue }?.let(onPayload) }
        .addOnCompleteListener { image.close() }
}

private fun isCameraGranted(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
