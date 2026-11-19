package com.reelwell.app

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class Detected(val platform: Platform, val url: String, val id: String)

object LinkDetector {
    private val IG_HOSTS = setOf("instagram.com", "instagr.am")
    private val FB_HOSTS = setOf("facebook.com", "fb.com", "fb.watch")
    private val TT_HOSTS = setOf("tiktok.com")
    private val OPT = setOf(RegexOption.IGNORE_CASE)

    fun bareHost(host: String): String = host.lowercase()
        .removePrefix("www.")
        .removePrefix("m.")
        .removePrefix("web.")
        .removePrefix("mbasic.")

    fun normalizeInput(raw: String): HttpUrl {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw LinkException("Paste a link first.")
        if (trimmed.length > 2000) throw LinkException("That link is too long.")
        val embedded = Regex("""https?://[^\s<>"']+""", OPT).find(trimmed)?.value
        val candidate = (embedded ?: trimmed).replace(Regex("""[)\].,;]+$"""), "")
        val withScheme = when {
            Regex("""^https?://""", OPT).containsMatchIn(candidate) -> candidate
            candidate.startsWith("//") -> "https:$candidate"
            else -> "https://$candidate"
        }
        val url = withScheme.toHttpUrlOrNull() ?: throw LinkException("That doesn’t look like a link.")
        return url.newBuilder().fragment(null).build()
    }

    private fun pathAndQuery(url: HttpUrl): String =
        url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")

    fun detect(raw: String): Detected {
        val url = normalizeInput(raw)
        val host = bareHost(url.host)
        val path = url.encodedPath

        if (host in IG_HOSTS) {
            val specific = Regex("""^/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)""", OPT).find(path)
                ?: Regex("""^/share/(?:reel/)?([A-Za-z0-9_-]+)""", OPT).find(path)
                ?: Regex("""^/stories/[^/]+/(\d+)""", OPT).find(path)
                ?: throw LinkException("Paste a link to a specific Instagram reel, post, or video — not a profile.")
            return Detected(Platform.INSTAGRAM, url.toString(), specific.groupValues[1])
        }

        if (host in FB_HOSTS || host.endsWith(".facebook.com")) {
            val short = host == "fb.watch" && path.length > 1
            val specific = short || Regex(
                """/(?:reel|reels|videos|watch|posts|share|video\.php|permalink\.php|story\.php)""", OPT,
            ).containsMatchIn(pathAndQuery(url))
            if (!specific) throw LinkException("Paste a Facebook reel, watch, or video link — not a profile.")
            val id = url.queryParameter("v")
                ?: Regex("""/(?:reel|videos|posts|share/[vrp])/(\d+)""", OPT).find(path)?.groupValues?.get(1)
                ?: path.split("/").filter { it.isNotEmpty() }.lastOrNull()
                ?: "facebook"
            return Detected(Platform.FACEBOOK, url.toString(), id.take(80))
        }

        if (host in TT_HOSTS || host == "vm.tiktok.com" || host == "vt.tiktok.com") {
            val short = host == "vm.tiktok.com" || host == "vt.tiktok.com" || path.startsWith("/t/")
            val video = Regex("""^/@[^/]+/(?:video|photo)/(\d+)""", OPT).find(path)
            if (!short && video == null) throw LinkException("Paste a TikTok video or photo link — not a profile.")
            val id = video?.groupValues?.get(1)
                ?: path.split("/").filter { it.isNotEmpty() }.lastOrNull()
                ?: "tiktok"
            return Detected(Platform.TIKTOK, url.toString(), id)
        }

        throw LinkException("Reelwell reads public Instagram, Facebook, and TikTok links.")
    }
}
