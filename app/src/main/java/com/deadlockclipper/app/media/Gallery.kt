package com.deadlockclipper.app.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.deadlockclipper.app.util.AppLog
import com.deadlockclipper.app.util.FriendlyError
import java.io.File

/** Saves finished clips into the phone's Movies/<folder> (visible in the gallery). */
object Gallery {
    fun relativePath(folder: String) = "${Environment.DIRECTORY_MOVIES}/$folder/"

    fun describe(folder: String) = "Movies/$folder"

    fun existingNames(context: Context, folder: String): List<String> {
        if (Build.VERSION.SDK_INT < 29) {
            return legacyDir(folder).list()?.toList() ?: emptyList()
        }
        val names = ArrayList<String>()
        try {
            context.contentResolver.query(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'deadlock_%'",
                arrayOf(relativePath(folder)), null,
            )?.use { c -> while (c.moveToNext()) names.add(c.getString(0)) }
        } catch (e: Exception) {
            AppLog.w("gallery", "Could not list existing clips: $e")
        }
        return names
    }

    @Suppress("DEPRECATION")
    private fun legacyDir(folder: String) = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), folder)

    /** Copies [file] into the gallery. Returns a content:// URI other apps can open. */
    fun save(context: Context, file: File, folder: String, name: String): Uri {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(folder))
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                    ?: throw IllegalStateException("MediaStore insert returned null")
                resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 20) } }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            }
            val dir = legacyDir(folder).apply { mkdirs() }
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(dest.path), arrayOf("video/mp4"), null)
            return FileProvider.getUriForFile(context, "${context.packageName}.files", dest)
        } catch (e: Exception) {
            throw FriendlyError(
                "Couldn't save the clip to ${describe(folder)}. Is the phone's storage full?",
                AppLog.stackTrace(e),
            )
        }
    }
}
