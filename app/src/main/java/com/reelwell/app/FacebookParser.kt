package com.reelwell.app

object FacebookParser {
    data class Parsed(val title: String, val thumbnail: String?, val duration: Double?, val media: List<MediaItem>)

    private fun decode(value: String): String = value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace(Regex("&#0*39;"), "'")
        .replace(Regex("&#x27;", RegexOption.IGNORE_CASE), "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private fun clockToSeconds(value: String): Double? {
        val m = Regex("""\b(\d{1,2}):(\d{2})(?::(\d{2}))?\b""").find(value) ?: return null
        val first = m.groupValues[1].toInt()
        val second = m.groupValues[2].toInt()
        val third = m.groupValues[3]
        return if (third.isNotEmpty()) (first * 3600 + second * 60 + third.toInt()).toDouble()
        else (first * 60 + second).toDouble()
    }

    fun parse(html: String): Parsed {
        val title = decode(Regex("<h3>([^<]*)</h3>").find(html)?.groupValues?.get(1)?.trim().orEmpty())
            .ifEmpty { "Facebook video" }
        val plain = decode(html.replace(Regex("<[^>]+>"), " ").replace(Regex("""\s+"""), " "))
        val duration = clockToSeconds(plain)
        val thumbnail = Regex("""<img[^>]+src="([^"]+)"""").findAll(html)
            .map { decode(it.groupValues[1]) }
            .firstOrNull { it.startsWith("https://") && !it.contains("static.xx.fbcdn.net") && !it.endsWith(".gif") }

        val media = mutableListOf<MediaItem>()
        val seen = mutableSetOf<String>()
        for (row in Regex("""<tr>([\s\S]*?)</tr>""").findAll(html)) {
            val chunk = row.groupValues[1]
            val href = Regex("""href="(https://[^"]+)"""").find(chunk)?.groupValues?.get(1) ?: continue
            val url = decode(href)
            if (!url.contains("snapcdn.app") && !url.contains("fbcdn.net") && !url.contains("rapidcdn.app")) continue
            if (!seen.add(url)) continue
            val cell = Regex("""<td[^>]*>([\s\S]*?)</td>""").find(chunk)?.groupValues?.get(1) ?: "Original"
            val quality = decode(cell.replace(Regex("<[^>]+>"), " ").replace(Regex("""\s+"""), " ").trim())
            val lower = quality.lowercase()
            if (lower == "render") continue
            val audio = Regex("mp3|audio|kbps").containsMatchIn(lower)
            media.add(
                MediaItem(
                    type = if (audio) MediaKind.AUDIO else MediaKind.VIDEO,
                    quality = quality.take(40).ifEmpty { "Original" },
                    ext = if (audio) "mp3" else "mp4",
                    bytes = null,
                    url = url,
                ),
            )
        }
        return Parsed(title, thumbnail, duration, media)
    }
}
