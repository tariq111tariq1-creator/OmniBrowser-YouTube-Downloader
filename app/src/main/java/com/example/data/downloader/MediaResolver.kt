package com.example.data.downloader

import com.example.data.diagnostics.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Resolves only user-provided direct media URLs.
 * Site-specific extractors and third-party YouTube proxy services are intentionally not used.
 */
data class ResolvedStream(
    val url: String,
    val quality: String,
    val mimeType: String = "video/mp4",
    val sizeBytes: Long = 0L,
    val isAudioOnly: Boolean = false,
    val isEstimatedSize: Boolean = false
)

object MediaResolver {
    private const val REMOTE_RESOLVER_BASE_URL = "https://omnibrowser-media-api.onrender.com"
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Accepts a direct media URL. YouTube and other page URLs are not treated as media streams.
     * This keeps the app limited to sources that expose an authorized, downloadable URL.
     */
    suspend fun resolveDirectDownloadStream(source: String, isAudio: Boolean = false): ResolvedStream? =
        withContext(Dispatchers.IO) {
            val value = source.trim()
            if (!value.startsWith("https://", true) && !value.startsWith("http://", true)) {
                DiagnosticLogger.w("MediaResolver", "الرابط ليس HTTP(S): $value")
                return@withContext null
            }

            if (YouTubeExtractor.isYouTubeUrl(value)) {
                DiagnosticLogger.i("MediaResolver", "تحويل رابط يوتيوب إلى المحلل المحلي On-Device Extractor: $value")
                YouTubeExtractor.resolveDirectStream(value, isAudio)?.let { return@withContext it }
                DiagnosticLogger.w("MediaResolver", "تعذر الاستخراج المحلي؛ تجربة خادم Render الاحتياطي")
                return@withContext resolveViaBackend(value, isAudio)
            }

            val request = Request.Builder()
                .url(value)
                .head()
                .header("Accept", "video/*,audio/*,application/vnd.apple.mpegurl,application/x-mpegURL,*/*")
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val contentType = response.header("Content-Type").orEmpty()
                        .substringBefore(';').trim().lowercase(Locale.US)
                    val finalUrl = response.request.url.toString()
                    val path = runCatching { response.request.url.encodedPath.lowercase(Locale.US) }.getOrDefault("")
                    val isHls = path.endsWith(".m3u8") || contentType.contains("mpegurl")
                    val isMedia = isMediaType(contentType) || isMediaPath(path) || isHls

                    if (response.isSuccessful && isMedia) {
                        val size = response.header("Content-Length")?.toLongOrNull() ?: 0L
                        val mime = when {
                            isHls -> "application/vnd.apple.mpegurl"
                            contentType.isNotBlank() && contentType != "application/octet-stream" -> contentType
                            else -> guessMimeType(finalUrl, isAudio)
                        }
                        val audio = isAudio || mime.startsWith("audio/")
                        return@withContext ResolvedStream(
                            url = finalUrl,
                            quality = if (isHls) "HLS غير محمي" else if (audio) "صوت مباشر" else "فيديو مباشر",
                            mimeType = mime,
                            sizeBytes = size,
                            isAudioOnly = audio
                        )
                    }
                }
            } catch (e: Exception) {
                DiagnosticLogger.w("MediaResolver", "تعذر فحص الرابط المباشر: ${e.message}")
            }

            DiagnosticLogger.w("MediaResolver", "الرابط لا يعلن عن ملف وسائط مباشر: $value")
            resolveViaBackend(value, isAudio)
        }

    private fun resolveViaBackend(source: String, isAudio: Boolean): ResolvedStream? {
        return try {
            val encoded = URLEncoder.encode(source, "UTF-8")
            val url = "$REMOTE_RESOLVER_BASE_URL/resolve?url=$encoded&audio=$isAudio"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val streamUrl = json.optString("stream_url").trim()
                if (streamUrl.isBlank()) return null
                ResolvedStream(
                    url = streamUrl,
                    quality = json.optString("quality", "remote") ,
                    mimeType = json.optString("mime_type", guessMimeType(streamUrl, isAudio)),
                    sizeBytes = json.optLong("filesize", 0L),
                    isAudioOnly = json.optBoolean("is_audio_only", isAudio),
                    isEstimatedSize = json.optLong("filesize", 0L) == 0L
                )
            }
        } catch (e: Exception) {
            DiagnosticLogger.w("MediaResolver", "تعذر الاتصال بخادم Render: ${e.message}")
            null
        }
    }

    private fun isMediaType(type: String): Boolean =
        type.startsWith("video/") || type.startsWith("audio/") ||
            type == "application/ogg" || type == "application/octet-stream"

    private fun isMediaPath(path: String): Boolean =
        listOf(".mp4", ".m4v", ".webm", ".mkv", ".mov", ".avi", ".mp3", ".m4a", ".aac", ".wav", ".flac", ".ogg")
            .any(path::endsWith)

    private fun guessMimeType(url: String, audio: Boolean): String {
        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url).lowercase(Locale.US)
        return when {
            decoded.contains(".mp3") -> "audio/mpeg"
            decoded.contains(".m4a") -> "audio/mp4"
            decoded.contains(".aac") -> "audio/aac"
            decoded.contains(".wav") -> "audio/wav"
            decoded.contains(".webm") -> if (audio) "audio/webm" else "video/webm"
            decoded.contains(".m3u8") -> "application/vnd.apple.mpegurl"
            audio -> "audio/*"
            else -> "video/mp4"
        }
    }
}
