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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Wallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.roamly.flights.Bcbp
import com.roamly.flights.PassDecoder
import com.roamly.ui.theme.ClayButton
import com.roamly.ui.theme.ClayOutlinedButton
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
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var legChoice by remember { mutableStateOf<PassDecoder.Found?>(null) }
    // One read per screen: the analyzer keeps firing while the pass is in view.
    var handled by remember { mutableStateOf(false) }

    fun deliver(found: PassDecoder.Found) {
        if (handled) return
        if (found.pass.legs.size > 1) { legChoice = found; return }
        handled = true
        onFound(found.pass, found.pass.legs[0], found.imageJpeg)
    }

    fun handle(outcome: PassDecoder.Outcome) {
        busy = false
        when (outcome) {
            is PassDecoder.Outcome.Ok -> deliver(outcome.found)
            PassDecoder.Outcome.NotAPass -> message = "That code isn't a boarding pass."
            PassDecoder.Outcome.NoBarcode -> message = "No barcode found in that image. Try a sharper screenshot."
            is PassDecoder.Outcome.Failed -> message = outcome.message
        }
    }

    fun decodeUri(uri: Uri) {
        busy = true
        message = null
        scope.launch { handle(PassDecoder.decodeUri(context, uri)) }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamera = it
        asked = true
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(::decodeUri)
    }
    val pickPass = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::decodeUri)
    }

    LaunchedEffect(initialUri) { initialUri?.let(::decodeUri) }
    LaunchedEffect(Unit) {
        if (!hasCamera && initialUri == null) permission.launch(Manifest.permission.CAMERA)
    }

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
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Reading the boarding pass…", color = Color.White)
                } else if (initialUri == null) {
                    Text(
                        if (asked) "Camera access is off, so Roamly can't scan live. You can still pick a screenshot or a wallet pass below."
                        else "Roamly needs the camera to scan a boarding pass.",
                        color = Color.White, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    if (asked) {
                        ClayOutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)))
                        }) { Text("Open app settings") }
                    } else {
                        ClayButton(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                    }
                }
            }
        }

        // Top bar
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = Color.White) }
            Text("Scan boarding pass", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }

        // Bottom: hint + pickers
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .background(Color.Black.copy(alpha = 0.55f)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                message ?: "Point the camera at the barcode on the pass, paper or screen.",
                color = if (message != null) MaterialTheme.colorScheme.error else Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ClayOutlinedButton(onClick = {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Icon(Icons.Rounded.Image, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Screenshot")
                }
                ClayOutlinedButton(onClick = {
                    pickPass.launch(arrayOf(PassDecoder.PKPASS_MIME, "application/zip", "application/octet-stream"))
                }) {
                    Icon(Icons.Rounded.Wallet, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Wallet pass")
                }
            }
        }
    }

    legChoice?.let { found ->
        AlertDialog(
            onDismissRequest = { legChoice = null },
            title = { Text("Which flight?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("This pass covers ${found.pass.legs.size} flights.")
                    found.pass.legs.forEach { leg ->
                        TextButton(onClick = {
                            legChoice = null
                            handled = true
                            onFound(found.pass, leg, found.imageJpeg)
                        }) { Text("${leg.flightNumber}  ${leg.from} → ${leg.to}  ${leg.date ?: ""}") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { legChoice = null }) { Text("Cancel") } },
        )
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
        // A guide frame in the middle third, where passes tend to be held.
        Box(
            Modifier.align(Alignment.Center).fillMaxWidth(0.82f).height(220.dp)
                .border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
        )
        IconButton(
            onClick = { torch = !torch; controller.enableTorch(torch) },
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
        ) {
            Icon(if (torch) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff, "Torch", tint = Color.White)
        }
    }
}
