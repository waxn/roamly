package com.roamly.flights

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Turns a camera frame, an image or a wallet pass into a parsed boarding pass.
 *
 * ML Kit is the bundled model (see libs.versions.toml), so this works without
 * Google Play Services. Only the formats boarding passes actually use are
 * enabled, which makes detection faster and rules out the stray QR code on a
 * poster behind the pass.
 */
object PassDecoder {

    /** What a decode produced: the pass, plus the image to keep with it. */
    data class Found(val pass: Bcbp.Pass, val imageJpeg: ByteArray?)

    sealed class Outcome {
        data class Ok(val found: Found) : Outcome()
        /** Read a barcode, but it was not a boarding pass. */
        object NotAPass : Outcome()
        /** No barcode in the image at all. */
        object NoBarcode : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    const val PKPASS_MIME = "application/vnd.apple.pkpass"

    fun newScanner(): BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_PDF417, Barcode.FORMAT_AZTEC,
                               Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX)
            .build()
    )

    /** The first barcode text in [texts] that parses as a boarding pass. */
    fun firstPass(texts: List<String?>): Bcbp.Pass? =
        texts.asSequence().filterNotNull().mapNotNull { Bcbp.parse(it) }.firstOrNull()

    /** Decode a shared or picked file: an image, or an Apple Wallet pass. */
    suspend fun decodeUri(context: Context, uri: Uri): Outcome = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val type = runCatching { resolver.getType(uri) }.getOrNull().orEmpty()
        val looksLikePkpass = type == PKPASS_MIME || uri.toString().lowercase().endsWith(".pkpass")
        if (looksLikePkpass || type == "application/zip" || type == "application/octet-stream") {
            val messages = runCatching {
                resolver.openInputStream(uri)?.use { PkPass.barcodeMessages(it) }
            }.getOrNull().orEmpty()
            if (messages.isNotEmpty()) {
                val pass = firstPass(messages) ?: return@withContext Outcome.NotAPass
                // A wallet pass has no photo of itself worth keeping.
                return@withContext Outcome.Ok(Found(pass, null))
            }
            if (looksLikePkpass) return@withContext Outcome.Failed("Could not read that wallet pass.")
        }

        val bitmap = runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return@withContext Outcome.Failed("That file is not an image Roamly can read.")
        decodeBitmap(bitmap)
    }

    suspend fun decodeBitmap(bitmap: Bitmap): Outcome {
        val scanner = newScanner()
        return try {
            val texts = suspendCancellableCoroutine<List<String?>?> { cont ->
                scanner.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { codes -> cont.resume(codes.map { it.rawValue }) }
                    .addOnFailureListener { cont.resume(null) }
            } ?: return Outcome.Failed("Could not scan that image.")
            if (texts.isEmpty()) return Outcome.NoBarcode
            val pass = firstPass(texts) ?: return Outcome.NotAPass
            Outcome.Ok(Found(pass, jpeg(bitmap)))
        } finally {
            scanner.close()
        }
    }

    /** JPEG for upload, capped so a huge photo doesn't cost a huge upload. */
    fun jpeg(bitmap: Bitmap, maxSide: Int = 2400): ByteArray {
        val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
        val b = if (scale < 1f) Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }
}
