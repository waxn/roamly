package com.roamly.ui.flights

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Wallet
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.roamly.flights.Bcbp
import com.roamly.flights.PassDecoder
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.AtlasSheet
import com.roamly.ui.kit.ButtonKind
import com.roamly.ui.kit.Feel
import com.roamly.ui.kit.IconAction
import com.roamly.ui.kit.IconTile
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.PanelTone
import com.roamly.ui.kit.rememberHaptics
import com.roamly.ui.theme.Atlas
import kotlinx.coroutines.launch

/**
 * Reads a boarding pass three ways: the live camera, an image picked from the
 * gallery (most phone passes live as screenshots), or a wallet `.pkpass` file.
 * [initialUri] is a file shared into Roamly, decoded straight away.
 *
 * On a read it hands the pass (and a photo of it) to [onFound]; a pass with
 * several legs asks which one first.
 */
@Composable
fun ScanPassScreen(
    initialUri: Uri?,
    onBack: () -> Unit,
    onFound: (Bcbp.Pass, Bcbp.Leg, ByteArray?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val c = Atlas.colors
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var legChoice by remember { mutableStateOf<PassDecoder.Found?>(null) }
    // One read per screen: the analyzer keeps firing while the pass is in view.
    var handled by remember { mutableStateOf(false) }

    fun deliver(found: PassDecoder.Found) {
        if (handled) return
        haptics.success()
        if (found.pass.legs.size > 1) { legChoice = found; return }
        handled = true
        onFound(found.pass, found.pass.legs[0], found.imageJpeg)
    }
    fun handle(outcome: PassDecoder.Outcome) {
        busy = false
        when (outcome) {
            is PassDecoder.Outcome.Ok -> deliver(outcome.found)
            PassDecoder.Outcome.NotAPass -> { haptics.reject(); message = "That code isn't a boarding pass." }
            PassDecoder.Outcome.NoBarcode -> { haptics.reject(); message = "No barcode in that image. Try a sharper screenshot." }
            is PassDecoder.Outcome.Failed -> { haptics.reject(); message = outcome.message }
        }
    }
    fun decodeUri(uri: Uri) {
        busy = true; message = null
        scope.launch { handle(PassDecoder.decodeUri(context, uri)) }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it; asked = true }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::decodeUri) }
    val pickPass = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::decodeUri) }

    LaunchedEffect(initialUri) { initialUri?.let(::decodeUri) }
    LaunchedEffect(Unit) { if (!hasCamera && initialUri == null) permission.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera && initialUri == null) {
            CameraPreview(onPass = { found -> if (!handled && legChoice == null) deliver(found) })
        } else {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (busy) {
                    CircularProgressIndicator(color = c.accent)
                    Spacer(Modifier.height(14.dp))
                    Text("Reading the boarding pass…", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                } else if (initialUri == null) {
                    IconTile(Icons.Rounded.PhotoCamera, tint = c.accent, size = 64.dp, iconSize = 30.dp)
                    Spacer(Modifier.height(18.dp))
                    Text(
                        if (asked) "Camera access is off, so Roamly can't scan live. You can still pick a screenshot or a wallet pass."
                        else "Roamly needs the camera to scan a boarding pass.",
                        color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(20.dp))
                    if (asked) {
                        AtlasButton("Open app settings", {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                        }, kind = ButtonKind.Secondary)
                    } else {
                        AtlasButton("Allow camera", { permission.launch(Manifest.permission.CAMERA) })
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.Rounded.ArrowBack, "Back", onBack, container = Color.Black.copy(alpha = 0.45f), tint = Color.White)
            Spacer(Modifier.width(12.dp))
            Text("Scan boarding pass", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Panel(Modifier.fillMaxWidth(), tone = PanelTone.Glass) {
                Text(
                    message ?: "Hold the barcode inside the frame — paper or screen both work.",
                    color = if (message != null) c.danger else c.text,
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AtlasButton("Screenshot", { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        Modifier.weight(1f), kind = ButtonKind.Secondary, compact = true, icon = Icons.Rounded.Image)
                    AtlasButton("Wallet pass", { pickPass.launch(arrayOf(PassDecoder.PKPASS_MIME, "application/zip", "application/octet-stream")) },
                        Modifier.weight(1f), kind = ButtonKind.Secondary, compact = true, icon = Icons.Rounded.Wallet)
                }
            }
        }
    }

    legChoice?.let { found ->
        AtlasSheet(onDismiss = { legChoice = null }, title = "Which flight?") {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This pass covers ${found.pass.legs.size} flights.", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                found.pass.legs.forEach { leg ->
                    Panel(Modifier.fillMaxWidth(), tone = PanelTone.Sunken, onClick = {
                        legChoice = null; handled = true
                        onFound(found.pass, leg, found.imageJpeg)
                    }) {
                        Text("${leg.from} → ${leg.to}", style = MaterialTheme.typography.titleMedium, color = c.text)
                        Text(listOfNotNull(leg.flightNumber, leg.date?.toString()).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(onPass: (PassDecoder.Found) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember { PassDecoder.newScanner() }
    val controller = remember { LifecycleCameraController(context) }
    var torch by remember { mutableStateOf(false) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            this.controller = controller
        }
    }
    DisposableEffect(lifecycleOwner) {
        val executor = ContextCompat.getMainExecutor(context)
        controller.setImageAnalysisAnalyzer(
            executor,
            MlKitAnalyzer(listOf(scanner), ImageAnalysis.COORDINATE_SYSTEM_ORIGINAL, executor) { result ->
                val texts = result.getValue(scanner)?.map { it.rawValue }.orEmpty()
                val pass = PassDecoder.firstPass(texts) ?: return@MlKitAnalyzer
                // The preview frame is the photo kept with the flight.
                val photo = previewView.bitmap?.let { PassDecoder.jpeg(it) }
                onPass(PassDecoder.Found(pass, photo))
            },
        )
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            scanner.close()
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Viewfinder(Modifier.align(Alignment.Center).fillMaxWidth(0.84f).height(230.dp))
        IconAction(
            if (torch) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff, "Torch",
            { torch = !torch; controller.enableTorch(torch) },
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
            container = Color.Black.copy(alpha = 0.45f), tint = if (torch) Atlas.colors.amber else Color.White, feel = Feel.Select,
        )
    }
}

/** Corner brackets around the target, with a scan line sweeping through it. */
@Composable
private fun Viewfinder(modifier: Modifier) {
    val accent = Atlas.colors.accent
    val t = rememberInfiniteTransition(label = "scan")
    val y by t.animateFloat(0.08f, 0.92f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "y")
    Canvas(modifier) {
        val len = 30.dp.toPx()
        val sw = 4.dp.toPx()
        val w = size.width; val h = size.height
        fun corner(x: Float, yy: Float, dx: Float, dy: Float) {
            drawLine(Color.White, Offset(x, yy), Offset(x + dx * len, yy), sw, StrokeCap.Round)
            drawLine(Color.White, Offset(x, yy), Offset(x, yy + dy * len), sw, StrokeCap.Round)
        }
        corner(0f, 0f, 1f, 1f); corner(w, 0f, -1f, 1f); corner(0f, h, 1f, -1f); corner(w, h, -1f, -1f)
        val ly = h * y
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.35f), Color.Transparent), ly - 26.dp.toPx(), ly + 26.dp.toPx()),
            Offset(8.dp.toPx(), ly - 26.dp.toPx()), androidx.compose.ui.geometry.Size(w - 16.dp.toPx(), 52.dp.toPx()),
        )
        drawLine(accent, Offset(10.dp.toPx(), ly), Offset(w - 10.dp.toPx(), ly), 2.dp.toPx(), StrokeCap.Round)
    }
}
