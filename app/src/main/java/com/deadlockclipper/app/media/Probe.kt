package com.deadlockclipper.app.media

import android.content.ContentResolver
import android.content.Context
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.deadlockclipper.app.util.AppLog
import com.deadlockclipper.app.util.FriendlyError
import com.deadlockclipper.core.VideoInfo
import java.io.File

object Probe {
    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return File(uri.path ?: "").name
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "video"
    }

    /** (size in bytes, last modified ms) - used to recognise a video we already scanned. */
    private fun sizeAndDate(context: Context, uri: Uri): Pair<Long, Long> {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val f = File(uri.path ?: "")
            return f.length() to f.lastModified()
        }
        var size = 0L
        var modified = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    val mi = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (mi >= 0 && !c.isNull(mi)) modified = c.getLong(mi)
                    if (modified == 0L) {
                        val di = c.getColumnIndex("date_modified") // MediaStore, in seconds
                        if (di >= 0 && !c.isNull(di)) modified = c.getLong(di) * 1000
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.w("probe", "Could not read file details: $e")
        }
        if (size <= 0) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
            } catch (_: Exception) {
            }
        }
        return size to modified
    }

    fun probe(context: Context, uri: Uri): VideoInfo {
        val ex = Codecs.openExtractor(context, uri)
        try {
            val vt = Codecs.findTrack(ex, "video/")
            if (vt < 0) throw FriendlyError("This file doesn't contain any video Android can read.")
            val f = ex.getTrackFormat(vt)
            val hasAudio = Codecs.findTrack(ex, "audio/") >= 0
            val mime = f.getString(MediaFormat.KEY_MIME) ?: "?"
            var w = f.getInteger(MediaFormat.KEY_WIDTH)
            var h = f.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = if (f.containsKey("rotation-degrees")) f.getInteger("rotation-degrees") else 0

            var durationUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
            var fps = 0.0
            if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                fps = try {
                    f.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
                } catch (_: Exception) {
                    f.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble()
                }
            }
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, uri)
                if (durationUs <= 0) durationUs = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) * 1000
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.let { if (it > 0) w = it }
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.let { if (it > 0) h = it }
                if (fps <= 0 && android.os.Build.VERSION.SDK_INT >= 28) {
                    val frames = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toDoubleOrNull() ?: 0.0
                    if (frames > 0 && durationUs > 0) fps = frames / (durationUs / 1e6)
                }
            } catch (e: Exception) {
                AppLog.w("probe", "MediaMetadataRetriever: $e")
            } finally {
                try {
                    mmr.release()
                } catch (_: Exception) {
                }
            }
            if (fps <= 1 || fps.isNaN()) fps = 30.0
            if (fps > 60.5) {
                AppLog.i("probe", "Source is %.2f fps; exports are capped at 60 fps for Shorts/Reels".format(fps))
                fps = 60.0
            }
            val duration = durationUs / 1e6
            if (duration <= 0) {
                throw FriendlyError(
                    "Couldn't read the length of this video. If it's an OBS recording that was interrupted, " +
                        "try remuxing it in OBS (File > Remux Recordings).",
                )
            }
            if (rotation == 90 || rotation == 270) {
                val t = w; w = h; h = t
            }
            val (size, modified) = sizeAndDate(context, uri)
            val info = VideoInfo(
                uri = uri.toString(), name = displayName(context, uri), duration = duration, width = w, height = h,
                fps = fps, hasAudio = hasAudio, vcodec = mime, sizeBytes = size, lastModified = modified, rotation = rotation,
            )
            if (Codecs.decoders(mime).isEmpty()) {
                throw FriendlyError("This phone can't decode this video's format ($mime). Try an H.264 (AVC) recording.")
            }
            AppLog.i("probe", "Video: $info")
            return info
        } finally {
            ex.release()
        }
    }
}
