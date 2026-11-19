package com.reelwell.app

import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import kotlin.math.pow

/** Port of the SnapSave packer decoder and result-page parser. */
object SnapSave {
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"
    private val OPT = setOf(RegexOption.IGNORE_CASE)

    private fun convertBase(value: String, from: Int, to: Int): String {
        val source = ALPHABET.substring(0, from)
        val target = ALPHABET.substring(0, to)
        var number = 0.0
        val chars = value.reversed()
        for (index in chars.indices) {
            val digit = source.indexOf(chars[index])
            if (digit != -1) number += digit * from.toDouble().pow(index)
        }
        if (!(number > 0)) return "0"
        val out = StringBuilder()
        while (number > 0) {
            val remainder = (number % to).toInt()
            out.insert(0, target[remainder])
            number = (number - remainder) / to
        }
        return if (out.isEmpty()) "0" else out.toString()
    }

    fun unpack(body: String): String? {
        val start = body.indexOf("eval(function(h,u,n,t,e,r)")
        if (start < 0) return null
        val call = body.indexOf("}(", start)
        if (call < 0 || body.getOrNull(call + 2) != '"') return null

        var cursor = call + 3
        val payload = StringBuilder()
        while (cursor < body.length) {
            val c = body[cursor]
            if (c == '\\') {
                val next = body.getOrNull(cursor + 1)
                if (next != null) payload.append(next)
                cursor += 2
                continue
            }
            if (c == '"') {
                cursor += 1
                break
            }
            payload.append(c)
            cursor += 1
        }

        val rest = Regex("""^,(\d+),"([^"]+)",(\d+),(\d+),(\d+)\)""").find(body.substring(cursor)) ?: return null
        val key = rest.groupValues[2]
        val subtract = rest.groupValues[3].toInt()
        val base = rest.groupValues[4].toInt()
        val delimiter = key.getOrNull(base) ?: return null

        val binary = StringBuilder()
        var index = 0
        while (index < payload.length) {
            val chunk = StringBuilder()
            while (index < payload.length && payload[index] != delimiter) {
                chunk.append(payload[index])
                index += 1
            }
            var text = chunk.toString()
            for (keyIndex in key.indices) {
                text = text.replace(key[keyIndex].toString(), keyIndex.toString())
            }
            val code = convertBase(text, base, 10).toDouble() - subtract
            if (code > 0 && code < 0x110000) binary.append(code.toInt().toChar())
            index += 1
        }

        val bytes = ByteArray(binary.length) { (binary[it].code and 255).toByte() }
        return String(bytes, Charsets.UTF_8)
    }

    private fun unescapeJs(value: String): String = value
        .replace("\\\"", "\"")
        .replace("\\'", "'")
        .replace("\\/", "/")
        .replace("\\n", "\n")
        .replace("\\r", "")
        .replace("\\\\", "\\")

    private fun jwtFilename(url: String): String? = try {
        val token = url.toHttpUrlOrNull()?.queryParameter("token")
        val part = token?.split(".")?.getOrNull(1)
        if (part == null) {
            null
        } else {
            val padded = part.padEnd(part.length + (4 - part.length % 4) % 4, '=')
            val json = String(Base64.decode(padded, Base64.URL_SAFE), Charsets.UTF_8)
            JSONObject(json).optString("filename").takeIf { it.isNotEmpty() }
        }
    } catch (e: Exception) {
        null
    }

    private fun extFromName(name: String?, fallback: String): String {
        val ext = name?.lowercase()?.let { Regex("""\.([a-z0-9]{2,5})$""").find(it)?.groupValues?.get(1) }
        return when (ext) {
            null -> fallback
            "jpeg" -> "jpg"
            else -> ext
        }
    }

    private fun kindFromExt(ext: String): MediaKind = when (ext) {
        "mp3", "m4a", "aac" -> MediaKind.AUDIO
        "jpg", "jpeg", "png", "webp" -> MediaKind.IMAGE
        else -> MediaKind.VIDEO
    }

    fun parse(body: String): Pair<String?, List<MediaItem>> {
        val decoded = unpack(body) ?: return null to emptyList()
        val htmlMatch = Regex("""innerHTML\s*=\s*"([\s\S]*)";\s*\}""").find(decoded)
        val html = unescapeJs(htmlMatch?.groupValues?.get(1) ?: decoded)

        val thumbnail = Regex("""<img[^>]+src="(https://[^"]+)"""", OPT).find(html)
            ?.groupValues?.get(1)?.replace("&amp;", "&")

        val media = mutableListOf<MediaItem>()
        val seen = mutableSetOf<String>()
        for (m in Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", OPT).findAll(html)) {
            val href = Regex("""href="([^"]+)"""", OPT).find(m.groupValues[1])
                ?.groupValues?.get(1)?.replace("&amp;", "&") ?: continue
            if (!href.startsWith("https://") || href.contains("play.google.com")) continue
            val url = href.toHttpUrlOrNull() ?: continue
            if (!url.host.endsWith("rapidcdn.app") || url.encodedPath.contains("thumb")) continue
            val full = url.toString()
            if (!seen.add(full)) continue

            val label = m.groupValues[2]
                .replace(Regex("<[^>]+>"), " ")
                .replace("&amp;", "&")
                .replace(Regex("""\s+"""), " ")
                .trim()
            val ext = extFromName(jwtFilename(full), if (Regex("audio|mp3", OPT).containsMatchIn(label)) "mp3" else "mp4")
            val type = kindFromExt(ext)
            val quality = when {
                Regex("download video", OPT).containsMatchIn(label) -> "MP4"
                Regex("download (photo|image)", OPT).containsMatchIn(label) -> "Photo"
                label.isNotEmpty() -> label
                type == MediaKind.IMAGE -> "Photo"
                else -> "Original"
            }
            media.add(MediaItem(type, quality.take(40), ext, null, full))
        }
        val videos = media.filter { it.type == MediaKind.VIDEO }
        val rest = media.filter { it.type != MediaKind.VIDEO }
        return thumbnail to (videos + rest)
    }
}
