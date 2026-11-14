package dev.lyra.music.online

import dev.lyra.music.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only way Lyra talks to the internet (docs/REQUIREMENTS.md §9.5).
 * HTTPS only, hosts must be on an allow-list, no cookies, automatic redirects are off (image
 * redirects are followed by hand and re-checked against the allow-list), and every body is
 * size-capped while it is being read. Downloads are returned as bytes / text – never saved as
 * files by this class and never executed.
 */
@Singleton
class SafeHttp @Inject constructor() {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** JSON / text from one of the fixed API hosts, or null on any failure. */
    fun getText(url: HttpUrl, referer: String? = null): String? {
        if (!url.isHttps || url.host !in API_HOSTS) return null
        return runCatching { read(url, referer, MAX_TEXT_BYTES)?.readUtf8() }.getOrNull()
    }

    /** Image bytes from an allow-listed image host, following at most 3 checked redirects. */
    fun getImage(rawUrl: String): ByteArray? = runCatching {
        var url = rawUrl.toHttpUrlOrNull() ?: return null
        repeat(4) {
            if (!url.isHttps || !MetadataParsers.isAllowedImageHost(url.host)) return null
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "image/*").build()
            client.newCall(request).execute().use { resp ->
                when (resp.code) {
                    200 -> {
                        val type = resp.header("Content-Type").orEmpty().lowercase()
                        if (!type.startsWith("image/")) return null
                        return readCapped(resp.body?.source() ?: return null, MAX_IMAGE_BYTES)?.readByteArray()
                    }
                    301, 302, 303, 307, 308 -> {
                        url = resp.header("Location")?.let { url.resolve(it) } ?: return null
                    }
                    else -> return null
                }
            }
        }
        null
    }.getOrNull()

    private fun read(url: HttpUrl, referer: String?, max: Long): Buffer? {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .apply { if (referer != null) header("Referer", referer) }
            .build()
        client.newCall(request).execute().use { resp ->
            if (resp.code != 200) return null
            return readCapped(resp.body?.source() ?: return null, max)
        }
    }

    private fun readCapped(source: okio.BufferedSource, max: Long): Buffer? {
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val n = source.read(buffer, 64 * 1024)
            if (n < 0) break
            total += n
            if (total > max) return null
        }
        return buffer
    }

    private companion object {
        const val MAX_TEXT_BYTES = 2L * 1024 * 1024
        const val MAX_IMAGE_BYTES = 15L * 1024 * 1024
        val API_HOSTS = setOf("lrclib.net", "music.163.com", "c.y.qq.com", "lyrics.kugou.com", "musicbrainz.org")
        val USER_AGENT = "LyraMusic/${BuildConfig.VERSION_NAME} (https://github.com/lefthand258/L-music)"
    }
}
