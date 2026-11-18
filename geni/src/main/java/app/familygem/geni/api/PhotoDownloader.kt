package app.familygem.geni.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Downloads a photo file from a signed (expiring) Geni CDN URL.
 * It needs no token: uses its own client without auth, and never follows redirects.
 */
class PhotoDownloader(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
    private val maxBytes: Long = 15_000_000,
    private val allowHttp: Boolean = false
) {

    /** Saves the photo to [target] and returns its content type. */
    suspend fun download(url: String, target: File): String = withContext(Dispatchers.IO) {
        val httpUrl = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid photo URL")
        require(httpUrl.isHttps || allowHttp) { "Photo URL must be https" }
        val part = File(target.parentFile, target.name + ".part")
        try {
            val request = Request.Builder().url(httpUrl).get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw GeniApiException(response.code, "Photo download failed: HTTP ${response.code}")
                }
                val type = response.header("Content-Type")?.substringBefore(';')?.trim().orEmpty()
                if (!type.startsWith("image/", ignoreCase = true)) {
                    throw IOException("Not an image: $type")
                }
                val body = response.body ?: throw IOException("Empty response")
                body.source().use { source ->
                    part.outputStream().use { out ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val n = source.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) throw IOException("Photo larger than $maxBytes bytes")
                            out.write(buffer, 0, n)
                        }
                    }
                }
                if (!part.renameTo(target)) {
                    target.delete()
                    if (!part.renameTo(target)) throw IOException("Cannot save ${target.name}")
                }
                type
            }
        } finally {
            part.delete()
        }
    }
}
