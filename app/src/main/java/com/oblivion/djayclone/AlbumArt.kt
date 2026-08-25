package com.oblivion.djayclone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pulls embedded cover art out of a track's own metadata (ID3/APIC for MP3,
 * MP4 'covr' atom, Vorbis comment picture block, etc.) - whatever container
 * the file is, MediaMetadataRetriever handles the common embedded-art tag
 * formats for us. No embedded art -> null, caller falls back to a plain
 * vinyl look.
 */
object AlbumArt {
    suspend fun extract(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val bytes = retriever.embeddedPicture ?: return@withContext null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (t: Throwable) {
            null
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
    }
}
