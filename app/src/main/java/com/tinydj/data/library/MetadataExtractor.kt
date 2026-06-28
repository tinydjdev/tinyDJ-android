package com.tinydj.data.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.tinydj.core.audio.AudioTrack

/** Pulls display metadata + format out of a content URI for the library. */
object MetadataExtractor {

    fun extract(context: Context, uri: Uri): AudioTrack {
        val display = displayName(context, uri)
        val mime = context.contentResolver.getType(uri).orEmpty()
        val isFlac = mime.contains("flac", ignoreCase = true) ||
            display.endsWith(".flac", ignoreCase = true)

        var title = display.substringBeforeLast('.')
        var artist = "unknown"
        var album = "unknown"
        var date = "unknown"
        var lastModified = 0L
        var duration = 0L

        // Try to query last modified timestamp from the document provider
        try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (idx >= 0) lastModified = c.getLong(idx)
                }
            }
        } catch (_: Throwable) {}

        if (lastModified == 0L) {
            lastModified = System.currentTimeMillis()
        }

        var trackNumber = 0

        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }?.let { title = it }
            var rawArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            if (rawArtist.isNullOrBlank() || rawArtist.equals("unknown", ignoreCase = true)) {
                rawArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            }
            rawArtist?.takeIf { it.isNotBlank() }?.let { artist = cleanArtist(it) }

            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() }?.let { album = it }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                ?.takeIf { it.isNotBlank() }?.let { date = it }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.takeIf { it.isNotBlank() }?.let {
                    val numStr = it.substringBefore('/')
                    trackNumber = numStr.toIntOrNull() ?: 0
                }
            duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Throwable) {
            // Unreadable metadata is fine; we keep the filename-derived defaults.
        } finally {
            runCatching { mmr.release() }
        }

        return AudioTrack(
            id = uri.toString(),
            uriString = uri.toString(),
            title = title,
            artist = artist,
            album = album,
            date = date,
            lastModified = lastModified,
            durationMs = duration,
            isFlac = isFlac,
            trackNumber = trackNumber,
        )
    }

    private fun cleanArtist(artist: String): String {
        if (artist.isBlank() || artist.equals("unknown", ignoreCase = true)) return "unknown"
        val separators = Regex("[,;/+]|\\s+(?:feat\\.?|featuring|ft\\.?|&|and|with|vs\\.?|x)\\s+", RegexOption.IGNORE_CASE)
        val parts = artist.split(separators)
        return parts.firstOrNull()?.trim() ?: artist
    }

    private fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return c.getString(idx) ?: "track"
                    }
                }
        } catch (_: Throwable) {}
        return uri.lastPathSegment ?: "track"
    }
}
