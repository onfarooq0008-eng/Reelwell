package com.reelwell.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast

object Downloader {
    fun fileName(r: ResolvedMedia, item: MediaItem): String {
        val slug = item.quality.lowercase().replace(Regex("[^a-z0-9]+"), "").take(16)
        val id = r.id.replace(Regex("[^a-zA-Z0-9_-]+"), "").take(40).ifEmpty { "media" }
        return "${r.platform.name.lowercase()}-$id${if (slug.isNotEmpty()) "-$slug" else ""}.${item.ext}"
    }

    private fun mime(ext: String): String = when (ext) {
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    fun enqueue(context: Context, item: MediaItem, name: String) {
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(item.url))
                .setTitle(name)
                .setMimeType(mime(item.ext))
                .addRequestHeader("User-Agent", Resolver.UA)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Reelwell/$name")
            dm.enqueue(request)
            Toast.makeText(context, "Downloading to Downloads/Reelwell", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Download failed. Resolve the link again.", Toast.LENGTH_LONG).show()
        }
    }
}
