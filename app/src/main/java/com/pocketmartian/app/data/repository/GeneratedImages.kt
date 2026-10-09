package com.pocketmartian.app.data.repository

import android.content.Context
import android.util.Base64
import java.io.File

/**
 * Pictures Grok made with Grok Imagine, as files in the app's private storage.
 *
 * The API hands each one back as base64 inside the reply; it is written out once and the
 * conversation refers to the file, so a saved chat keeps its pictures without holding
 * megabytes of text, and the image viewer, Save and Share all work from the same file.
 */
internal object GeneratedImages {

    private const val DIR = "generated"

    fun directory(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** Write one image and return its file, named after the API's id for it. */
    fun save(context: Context, id: String, base64: String): File {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val safeId = id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifBlank { "image" }
        val file = File(directory(context), "$safeId-${System.currentTimeMillis()}.${extensionFor(bytes)}")
        file.writeBytes(bytes)
        return file
    }

    /** Whether a picture shown in the chat is one of these, rather than one the user attached. */
    fun isGenerated(context: Context, path: String?): Boolean =
        path != null && File(path).parentFile?.canonicalPath == directory(context).canonicalPath

    /**
     * Delete pictures no saved conversation refers to any more: those from chats that were
     * cleared, or replaced by a new one. [keep] holds the paths still in use.
     */
    fun purgeUnreferenced(context: Context, keep: Set<String>) {
        val kept = keep.mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }.toSet()
        directory(context).listFiles()?.forEach { file ->
            if (file.canonicalPath !in kept) file.delete()
        }
    }
}

/** The file type the image's own first bytes declare; the API does not say. */
internal fun extensionFor(bytes: ByteArray): String = when {
    bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
        bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "png"
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "jpg"
    bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
    else -> "png"
}
