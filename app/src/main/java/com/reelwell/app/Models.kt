package com.reelwell.app

enum class Platform(val label: String) {
    INSTAGRAM("Instagram"),
    FACEBOOK("Facebook"),
    TIKTOK("TikTok"),
}

enum class MediaKind { VIDEO, AUDIO, IMAGE }

data class MediaItem(
    val type: MediaKind,
    val quality: String,
    val ext: String,
    val bytes: Long?,
    val url: String,
)

data class ResolvedMedia(
    val platform: Platform,
    val id: String,
    val title: String,
    val author: String?,
    val thumbnail: String?,
    val duration: Double?,
    val sourceUrl: String,
    val media: List<MediaItem>,
)

class LinkException(message: String) : Exception(message)
class ResolveException(message: String) : Exception(message)
