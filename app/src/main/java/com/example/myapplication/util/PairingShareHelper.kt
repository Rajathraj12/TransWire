package com.example.myapplication.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Helpers for sharing pairing QR/PIN invites via Android's system share sheet
 * (WhatsApp, Instagram, SMS, etc.) and decoding uploaded QR images on the receiver.
 */
object PairingShareHelper {

    private const val SHARE_DIR = "share"
    private const val SHARE_QR_FILENAME = "transwire_pairing_qr.png"

    fun sharePin(context: Context, pin: String, timeRemainingSeconds: Long) {
        if (pin.length != 6 || !pin.all { it.isDigit() }) return
        val formattedPin = "${pin.substring(0, 3)} ${pin.substring(3)}"
        val mins = (timeRemainingSeconds / 60).coerceAtLeast(0)
        val secs = (timeRemainingSeconds % 60).coerceAtLeast(0)
        val validityNote = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"

        val shareText = buildString {
            appendLine("TransWire File Transfer")
            appendLine("PIN: $formattedPin (expires in $validityNote)")
            append("Open TransWire → Receive → Enter PIN to connect.")
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "TransWire Pairing PIN")
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        launchChooser(context, intent, "Share PIN via")
    }

    suspend fun shareQrCode(context: Context, qrBitmap: Bitmap, pin: String?) {
        val shareUri = withContext(Dispatchers.IO) {
            runCatching {
                val shareDir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
                val outFile = File(shareDir, SHARE_QR_FILENAME)
                val padded = renderShareableQrBitmap(qrBitmap)
                FileOutputStream(outFile).use { stream ->
                    padded.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                if (padded !== qrBitmap) padded.recycle()
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
            }.getOrNull()
        }

        if (shareUri == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Unable to prepare QR image for sharing.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val pinLine = pin
            ?.takeIf { it.length == 6 && it.all(Char::isDigit) }
            ?.let { "Or enter PIN: ${it.substring(0, 3)} ${it.substring(3)}\n" }
            .orEmpty()

        val caption = "TransWire File Transfer\n" +
            pinLine +
            "Open TransWire → Receive → Upload QR to connect."

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            putExtra(Intent.EXTRA_TEXT, caption)
            clipData = ClipData.newUri(context.contentResolver, "TransWire QR Code", shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        withContext(Dispatchers.Main) {
            launchChooser(context, intent, "Share QR code via", grantReadPermission = true)
        }
    }

    fun cleanupSharedQrFile(context: Context) {
        runCatching {
            val file = File(File(context.cacheDir, SHARE_DIR), SHARE_QR_FILENAME)
            if (file.exists()) file.delete()
        }
    }

    suspend fun decodeQrFromUri(context: Context, uri: Uri): String? {
        val mlKitResult = decodeWithMlKit(context, uri)
        if (!mlKitResult.isNullOrBlank()) return mlKitResult
        return withContext(Dispatchers.IO) { decodeWithZxing(context, uri) }
    }

    private suspend fun decodeWithMlKit(context: Context, uri: Uri): String? {
        return try {
            val image = InputImage.fromFilePath(context, uri)
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            val scanner = BarcodeScanning.getClient(options)
            val result = CompletableDeferred<String?>()
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val raw = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                    result.complete(raw)
                }
                .addOnFailureListener { result.complete(null) }
                .addOnCanceledListener { result.complete(null) }
            result.await()
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeWithZxing(context: Context, uri: Uri): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            if (maxDim <= 0) return null
            var sampleSize = 1
            while (maxDim / sampleSize > 1200) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            try {
                val width = bitmap.width
                val height = bitmap.height
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                val source = RGBLuminanceSource(width, height, pixels)
                val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                val hints = mapOf(
                    DecodeHintType.TRY_HARDER to true,
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
                )
                MultiFormatReader().decode(binaryBitmap, hints).text
            } finally {
                bitmap.recycle()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Adds a solid white quiet zone so social apps don't crop or blur QR finder patterns. */
    private fun renderShareableQrBitmap(qrBitmap: Bitmap): Bitmap {
        val targetSize = 640
        val margin = 56
        val out = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val scaled = Bitmap.createScaledBitmap(qrBitmap, targetSize - margin * 2, targetSize - margin * 2, false)
        canvas.drawBitmap(scaled, margin.toFloat(), margin.toFloat(), null)
        if (scaled !== qrBitmap) scaled.recycle()
        return out
    }

    private fun launchChooser(
        context: Context,
        shareIntent: Intent,
        title: String,
        grantReadPermission: Boolean = false
    ) {
        val chooser = Intent.createChooser(shareIntent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (grantReadPermission) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        try {
            context.startActivity(chooser)
        } catch (_: Exception) {
            Toast.makeText(context, "No app available to share.", Toast.LENGTH_SHORT).show()
        }
    }
}

