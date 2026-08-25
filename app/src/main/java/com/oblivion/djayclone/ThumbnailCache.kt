package com.oblivion.djayclone

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Thumbnail loading for LibraryScreen's scrolling row list. Split by
 * SOURCE, not just API level: ContentResolver.loadThumbnail() only accepts
 * MediaStore-backed content:// uris - it doesn't work for manual/SAF rows
 * at ANY API level - and only exists on API 29+. Everything else falls
 * back to a decode-once-and-cache-to-disk path using the same
 * MediaMetadataRetriever technique AlbumArt.kt already uses for the
 * per-deck turntable art.
 *
 * The in-memory LruCache exists purely to avoid redundant loadThumbnail()
 * IPC round-trips / redundant file reads during fast scrolling - on
 * API29+ MediaStore rows, the OS has already absorbed the real decode cost
 * via its own on-disk thumbnail cache; this isn't trying to avoid that.
 */
object ThumbnailCache {
    private const val THUMB_PX = 128

    private val memCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 32).toInt().coerceAtLeast(1)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun load(context: Context, track: LibraryTrack): Bitmap? = withContext(Dispatchers.IO) {
        val key = track.id.cacheKey()
        memCache.get(key)?.let { return@withContext it }

        val bmp = try {
            if (!track.isManual && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(track.uri, Size(THUMB_PX, THUMB_PX), null)
            } else {
                loadFallback(context, track)
            }
        } catch (_: Throwable) {
            // loadThumbnail throws if the row has no embedded/derivable art -
            // fall back rather than leaving the row permanently blank on a
            // transient failure.
            loadFallback(context, track)
        }

        bmp?.also { memCache.put(key, it) }
    }

    private fun loadFallback(context: Context, track: LibraryTrack): Bitmap? {
        val cache = TrackCacheRepository.get(context.applicationContext as Application)
        val cachedPath = cache.getCachedThumbPath(track.id)
        if (cachedPath != null) {
            val file = File(cachedPath)
            if (file.exists()) BitmapFactory.decodeFile(cachedPath)?.let { return it }
        }
        val retriever = MediaMetadataRetriever()
        val full = try {
            retriever.setDataSource(context, track.uri)
            val bytes = retriever.embeddedPicture ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) {
            null
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
        val downsampled = full?.let {
            val longSide = maxOf(it.width, it.height).coerceAtLeast(1)
            val scale = THUMB_PX.toFloat() / longSide
            if (scale < 1f) {
                it.scale((it.width * scale).toInt().coerceAtLeast(1), (it.height * scale).toInt().coerceAtLeast(1))
            } else {
                it
            }
        } ?: return null

        try {
            val dir = File(context.cacheDir, "thumb_cache").apply { mkdirs() }
            val outFile = File(dir, "${track.id.cacheKey()}.jpg")
            FileOutputStream(outFile).use { out -> downsampled.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            cache.setCachedThumbPath(track.id, outFile.absolutePath)
        } catch (_: Throwable) {
            // Cache write failure is non-fatal - the bitmap is still
            // returned for this composition, just not persisted to disk.
        }
        return downsampled
    }
}
