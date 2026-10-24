package com.roamly.flights

import com.google.gson.JsonParser
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Reads the barcode text out of an Apple Wallet `.pkpass` file — a zip whose
 * `pass.json` carries it as `barcodes[].message` (or, in older passes, a
 * single `barcode.message`). No image decoding needed: the text is right there.
 */
object PkPass {
    private const val MAX_PASS_JSON = 512 * 1024

    fun barcodeMessages(input: InputStream): List<String> {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return emptyList()
                if (entry.name != "pass.json") continue
                val bytes = zip.readNBytesCompat(MAX_PASS_JSON)
                val root = runCatching {
                    JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
                }.getOrNull() ?: return emptyList()
                val out = mutableListOf<String>()
                root.getAsJsonArray("barcodes")?.forEach { b ->
                    runCatching { b.asJsonObject.get("message")?.asString }.getOrNull()?.let(out::add)
                }
                runCatching { root.getAsJsonObject("barcode")?.get("message")?.asString }
                    .getOrNull()?.let(out::add)
                return out.distinct()
            }
        }
    }

    private fun InputStream.readNBytesCompat(max: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (buf.size() < max) {
            val n = read(chunk, 0, minOf(chunk.size, max - buf.size()))
            if (n <= 0) break
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }
}
