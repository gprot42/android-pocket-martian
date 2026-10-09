package com.pocketmartian.app.data.share

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Where pictures saved to the gallery go, under Pictures. */
private const val GALLERY_FOLDER = "Pocket Martian"

/** Shown to the user after a save, matching where [saveImageToGallery] puts things. */
const val GALLERY_PLACE = "Pictures/$GALLERY_FOLDER"

/**
 * Copy a picture (a scan, or one Grok made) into the phone's gallery, in
 * Pictures/Pocket Martian, and return that folder to tell the user. The shared media
 * store takes it without any permission from Android 10 on; earlier versions would need
 * storage permission, so there the picture is left to Share.
 */
fun saveImageToGallery(context: Context, file: File): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        throw UnsupportedOperationException("this needs Android 10 or later; use Share instead")
    }
    val mime = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$GALLERY_FOLDER")
            // Hidden from other apps until the copy is complete.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    ) ?: throw IOException("the gallery refused the picture")
    try {
        val out = resolver.openOutputStream(uri) ?: throw IOException("couldn't write to the gallery")
        out.use { file.inputStream().use { input -> input.copyTo(it) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    } catch (e: Throwable) {
        resolver.delete(uri, null, null)
        throw e
    }
    return GALLERY_PLACE
}
