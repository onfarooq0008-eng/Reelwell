package com.reelwell.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

object Resolver {
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val noRedirect = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    private fun call(c: OkHttpClient, req: Request, seconds: Long): Response =
        c.newBuilder().callTimeout(seconds, TimeUnit.SECONDS).build().newCall(req).execute()

    private fun JSONObject.str(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

    suspend fun resolve(raw: String): ResolvedMedia = withContext(Dispatchers.IO) {
        var detected = try {
            LinkDetector.detect(raw)
        } catch (e: LinkException) {
            throw ResolveException(e.message ?: "That link isn’t supported.")
        }
        val expanded = expandShortLink(detected.url, 0)
        if (expanded != detected.url) {
            detected = try {
                LinkDetector.detect(expanded)
            } catch (e: LinkException) {
                detected.copy(url = expanded)
            }
        }
        try {
            when (detected.platform) {
                Platform.TIKTOK -> resolveTikTok(detected.url, detected.id)
                Platform.INSTAGRAM -> resolveInstagram(detected.url, detected.id)
                Platform.FACEBOOK -> resolveFacebook(detected.url, detected.id)
            }
        } catch (e: ResolveException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedIOException) {
            throw ResolveException("The source took too long. Try again.")
        } catch (e: IOException) {
            throw ResolveException("Could not reach the source. Try again in a moment.")
        } catch (e: Exception) {
            throw ResolveException("Could not read that link. Check that the post is public.")
        }
    }

    // ---- short links -------------------------------------------------------------------------

    private fun expandShortLink(raw: String, depth: Int): String {
        if (depth > 3) return raw
        val url = raw.toHttpUrlOrNull() ?: return raw
        val host = LinkDetector.bareHost(url.host).removePrefix("mbasic.")
        val path = url.encodedPath
        val short = host == "vm.tiktok.com" || host == "vt.tiktok.com" || host == "fb.watch" ||
            host == "fb.com" || host == "instagr.am" || path.startsWith("/share/") || path.startsWith("/t/")
        if (!short) return raw
        return try {
            val req = Request.Builder().url(url).header("user-agent", UA).header("accept", "text/html").build()
            call(noRedirect, req, 12).use { r ->
                val location = r.header("location")
                if (location != null) {
                    val next = url.resolve(location)?.toString()
                    if (next != null && next != raw) return@use expandShortLink(next, depth + 1)
                }
                if (r.isSuccessful) {
                    val html = r.body?.string().orEmpty()
                    val href = Regex(
                        """https://(?:www\.)?(?:instagram\.com|facebook\.com|tiktok\.com|m\.tiktok\.com)/[^"'<\s]+""",
                        RegexOption.IGNORE_CASE,
                    ).find(html)?.value
                    if (href != null) return@use href.replace("&amp;", "&").substringBefore("#")
                }
                raw
            }
        } catch (e: Exception) {
            raw
        }
    }

    // ---- TikTok ------------------------------------------------------------------------------

    private fun pushVideo(media: MutableList<MediaItem>, url: String?, quality: String, bytes: Long?) {
        if (url == null || !url.startsWith("https://")) return
        if (media.any { it.url == url }) return
        media.add(MediaItem(MediaKind.VIDEO, quality, "mp4", bytes, url))
    }

    private fun resolveTikTok(sourceUrl: String, id: String): ResolvedMedia {
        try {
            val body = FormBody.Builder().add("url", sourceUrl).add("hd", "1").build()
            val req = Request.Builder().url("https://www.tikwm.com/api/").post(body)
                .header("user-agent", UA).header("accept", "application/json").build()
            val text = call(client, req, 25).use { r ->
                if (!r.isSuccessful) throw IllegalStateException("tikwm status")
                r.body?.string().orEmpty()
            }
            val payload = JSONObject(text)
            val data = payload.optJSONObject("data")
            if (payload.optInt("code", -1) != 0 || data == null) throw IllegalStateException("empty")

            val media = mutableListOf<MediaItem>()
            val hd = data.str("hdplay")
            pushVideo(media, hd, "HD", data.optLong("hd_size", 0).takeIf { it > 0 })
            pushVideo(media, data.str("play"), if (hd != null) "No watermark" else "MP4", data.optLong("size", 0).takeIf { it > 0 })
            val wm = data.str("wmplay")
            if (wm != null && wm != data.str("play")) {
                pushVideo(media, wm, "Watermark", data.optLong("wm_size", 0).takeIf { it > 0 })
            }
            val images: JSONArray? = data.optJSONArray("images")
            if (images != null) {
                for (i in 0 until images.length()) {
                    val u = images.optString(i)
                    if (u.startsWith("https://")) media.add(MediaItem(MediaKind.IMAGE, "Slide ${i + 1}", "jpg", null, u))
                }
            }
            val music = data.str("music")
            if (music != null && music.startsWith("https://")) {
                media.add(MediaItem(MediaKind.AUDIO, "Original audio", "mp3", null, music))
            }
            if (media.isEmpty()) throw IllegalStateException("no media")

            val author = data.optJSONObject("author")?.let { a ->
                a.str("unique_id")?.let { "@$it" } ?: a.str("nickname")
            }
            return ResolvedMedia(
                platform = Platform.TIKTOK,
                id = data.str("id") ?: id,
                title = (data.str("title") ?: "TikTok video").trim().take(180).ifEmpty { "TikTok video" },
                author = author,
                thumbnail = data.str("origin_cover") ?: data.str("cover"),
                duration = if (data.has("duration")) data.optDouble("duration") else null,
                sourceUrl = sourceUrl,
                media = media,
            )
        } catch (e: ResolveException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return resolveTikTokDirect(sourceUrl, id)
        }
    }

    private fun resolveTikTokDirect(sourceUrl: String, id: String): ResolvedMedia {
        val req = Request.Builder().url(sourceUrl).header("user-agent", UA).header("accept", "text/html").build()
        val html = call(client, req, 20).use { r ->
            if (!r.isSuccessful) throw ResolveException("TikTok did not return this video.")
            r.body?.string().orEmpty()
        }
        val jsonText = html
            .substringAfter("""<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">""", "")
            .substringBefore("</script>")
        if (jsonText.isEmpty()) throw ResolveException("TikTok did not include a public video on that page.")
        val detail = JSONObject(jsonText).optJSONObject("__DEFAULT_SCOPE__")?.optJSONObject("webapp.video-detail")
        if (detail == null || detail.optInt("statusCode", 0) != 0) {
            throw ResolveException(detail?.str("statusMsg") ?: "This TikTok is private, deleted, or not a public video.")
        }
        val item = detail.optJSONObject("itemInfo")?.optJSONObject("itemStruct")
            ?: throw ResolveException("TikTok returned this post without a downloadable file.")

        val media = mutableListOf<MediaItem>()
        val video = item.optJSONObject("video")
        pushVideo(media, video?.str("playAddr"), "No watermark", null)
        pushVideo(media, video?.str("downloadAddr"), "Original", null)
        val imgs = item.optJSONObject("imagePost")?.optJSONArray("images")
        if (imgs != null) {
            for (i in 0 until imgs.length()) {
                val list = imgs.optJSONObject(i)?.optJSONObject("imageURL")?.optJSONArray("urlList") ?: continue
                for (j in 0 until list.length()) {
                    val u = list.optString(j)
                    if (u.startsWith("https://")) {
                        media.add(MediaItem(MediaKind.IMAGE, "Slide ${i + 1}", "jpg", null, u))
                        break
                    }
                }
            }
        }
        val music = item.optJSONObject("music")?.str("playUrl")
        if (music != null && music.startsWith("https://")) {
            media.add(MediaItem(MediaKind.AUDIO, "Original audio", "mp3", null, music))
        }
        if (media.isEmpty()) throw ResolveException("TikTok returned this post without a downloadable file.")

        val author = item.optJSONObject("author")?.let { a ->
            a.str("uniqueId")?.let { "@$it" } ?: a.str("nickname")
        }
        return ResolvedMedia(
            platform = Platform.TIKTOK,
            id = item.str("id") ?: id,
            title = (item.str("desc") ?: "TikTok video").trim().take(180).ifEmpty { "TikTok video" },
            author = author,
            thumbnail = null,
            duration = if (video != null && video.has("duration")) video.optDouble("duration") else null,
            sourceUrl = sourceUrl,
            media = media,
        )
    }

    // ---- Instagram ---------------------------------------------------------------------------

    private data class InstaMeta(val title: String?, val author: String?, val thumbnail: String?)

    private fun instagramMeta(sourceUrl: String): InstaMeta = try {
        val endpoint = "https://i.instagram.com/api/v1/oembed/".toHttpUrl().newBuilder()
            .addQueryParameter("url", sourceUrl).build()
        val req = Request.Builder().url(endpoint).header("user-agent", UA).header("x-ig-app-id", "936619743392459").build()
        call(client, req, 12).use { r ->
            if (!r.isSuccessful) {
                InstaMeta(null, null, null)
            } else {
                val p = JSONObject(r.body?.string().orEmpty())
                InstaMeta(
                    title = p.str("title")?.trim()?.lines()?.firstOrNull()?.take(180),
                    author = p.str("author_name")?.trim(),
                    thumbnail = p.str("thumbnail_url")?.takeIf { it.startsWith("https://") },
                )
            }
        }
    } catch (e: Exception) {
        InstaMeta(null, null, null)
    }

    private fun snapSaveRequest(sourceUrl: String): Request {
        val body = FormBody.Builder().add("url", sourceUrl).build()
        return Request.Builder().url("https://snapsave.app/action.php?lang=en").post(body)
            .header("user-agent", UA)
            .header("origin", "https://snapsave.app")
            .header("referer", "https://snapsave.app/")
            .header("accept", "*/*")
            .build()
    }

    private fun resolveInstagram(sourceUrl: String, id: String): ResolvedMedia {
        val meta = instagramMeta(sourceUrl)
        val (ok, text) = call(client, snapSaveRequest(sourceUrl), 30).use { it.isSuccessful to it.body?.string().orEmpty() }
        if (!ok) throw ResolveException("Instagram did not return this post. It may be private, deleted, or not a public reel.")
        val (thumb, media) = SnapSave.parse(text)
        if (media.isEmpty()) throw ResolveException("No public video or photo was found for that Instagram link.")
        val reel = Regex("""/(reel|reels)/""", RegexOption.IGNORE_CASE).containsMatchIn(sourceUrl)
        return ResolvedMedia(
            platform = Platform.INSTAGRAM,
            id = id,
            title = meta.title ?: if (reel) "Instagram reel" else "Instagram post",
            author = meta.author,
            thumbnail = thumb ?: meta.thumbnail,
            duration = null,
            sourceUrl = sourceUrl,
            media = media,
        )
    }

    // ---- Facebook ----------------------------------------------------------------------------

    private fun resolveFacebook(sourceUrl: String, id: String): ResolvedMedia {
        val form = FormBody.Builder().add("q", sourceUrl).add("t", "media").add("lang", "en").build()
        val req = Request.Builder().url("https://fbdownloader.to/api/ajaxSearch").post(form)
            .header("user-agent", UA)
            .header("origin", "https://fbdownloader.to")
            .header("referer", "https://fbdownloader.to/")
            .header("x-requested-with", "XMLHttpRequest")
            .header("accept", "application/json")
            .build()
        val text = call(client, req, 30).use { r ->
            if (!r.isSuccessful) throw ResolveException("Facebook did not respond. Try the reel or video link again.")
            r.body?.string().orEmpty()
        }
        val payload = JSONObject(text)
        val html = payload.str("data").orEmpty()
        val parsed = if (html.isNotEmpty()) FacebookParser.parse(html) else null
        if (parsed != null && parsed.media.isNotEmpty()) {
            return ResolvedMedia(
                platform = Platform.FACEBOOK,
                id = id,
                title = parsed.title,
                author = null,
                thumbnail = parsed.thumbnail,
                duration = parsed.duration,
                sourceUrl = sourceUrl,
                media = parsed.media,
            )
        }

        val fallback = call(client, snapSaveRequest(sourceUrl), 30).use { it.body?.string().orEmpty() }
        val (thumb, media) = SnapSave.parse(fallback)
        if (media.isEmpty()) {
            val hint = payload.str("mess")?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("""\s+"""), " ")?.trim()
            throw ResolveException(hint?.takeIf { it.isNotEmpty() } ?: "No public Facebook video was found for that link.")
        }
        return ResolvedMedia(
            platform = Platform.FACEBOOK,
            id = id,
            title = "Facebook video",
            author = null,
            thumbnail = thumb,
            duration = null,
            sourceUrl = sourceUrl,
            media = media,
        )
    }
}
