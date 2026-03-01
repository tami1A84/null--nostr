package io.nurunuru.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.URI
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Image optimization utilities with bandwidth-based sizing.
 * Synced with web version: lib/imageUtils.js
 */
object ImageOptimizer {

    // 低帯域モードのしきい値 (1.5 Mbps = 1500 kbps)
    private const val LOW_BANDWIDTH_THRESHOLD_KBPS = 1500

    // ─── Bandwidth Detection ─────────────────────────────────────────────────

    fun isLowBandwidth(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return true
        val capabilities = cm.getNetworkCapabilities(network) ?: return true

        val downstreamKbps = capabilities.linkDownstreamBandwidthKbps
        return downstreamKbps < LOW_BANDWIDTH_THRESHOLD_KBPS
    }

    // ─── Image Size Optimization ─────────────────────────────────────────────

    data class ImageSize(val width: Int, val height: Int)

    fun getOptimalImageSize(
        originalWidth: Int,
        originalHeight: Int,
        context: Context
    ): ImageSize {
        val lowBandwidth = isLowBandwidth(context)
        val maxWidth = if (lowBandwidth) 480 else 1024
        val maxHeight = if (lowBandwidth) 480 else 1024

        if (originalWidth <= maxWidth && originalHeight <= maxHeight) {
            return ImageSize(originalWidth, originalHeight)
        }

        val ratio = min(maxWidth.toFloat() / originalWidth, maxHeight.toFloat() / originalHeight)
        return ImageSize(
            (originalWidth * ratio).roundToInt(),
            (originalHeight * ratio).roundToInt()
        )
    }

    fun getOptimalQuality(context: Context): Float =
        if (isLowBandwidth(context)) 0.6f else 0.85f

    // ─── Image Proxy ─────────────────────────────────────────────────────────

    private val TRUSTED_IMAGE_DOMAINS = setOf(
        "nostr.build", "image.nostr.build", "pfp.nostr.build", "blossom.nostr.build",
        "void.cat", "media.snort.social", "cdn.jb55.com", "nostr.download",
        "blossom.primal.net", "yabu.me", "share.yabu.me", "kojira.io", "r.kojira.io",
        "imgproxy.iris.to", "wsrv.nl",
        "i.imgur.com", "imgur.com", "gyazo.com", "i.gyazo.com",
        "gravatar.com", "githubusercontent.com", "robohash.org",
        "api.dicebear.com", "dicebear.com"
    )

    private val CORP_PROBLEM_DOMAINS = setOf(
        "cdninstagram.com", "scontent.cdninstagram.com",
        "instagram.com", "fbcdn.net", "twimg.com", "pbs.twimg.com"
    )

    fun needsProxy(url: String): Boolean {
        if (url.isBlank() || url.startsWith("data:")) return false
        return try {
            val hostname = URI(url).host?.lowercase() ?: return false
            if (hostname.contains("localhost") || hostname.contains("127.0.0.1")) return false
            if (TRUSTED_IMAGE_DOMAINS.any { hostname.contains(it) }) return false
            CORP_PROBLEM_DOMAINS.any { hostname.contains(it) }
        } catch (_: Exception) { false }
    }

    fun getProxiedUrl(url: String, proxyIndex: Int = 0): String {
        if (url.isBlank() || url.startsWith("data:")) return url
        if (url.contains("imgproxy.iris.to") || url.contains("wsrv.nl")) return url

        return when (proxyIndex) {
            0 -> "https://imgproxy.iris.to/insecure/plain/${java.net.URLEncoder.encode(url, "UTF-8")}"
            else -> "https://wsrv.nl/?url=${java.net.URLEncoder.encode(url, "UTF-8")}"
        }
    }

    fun getImageUrl(url: String, forceProxy: Boolean = false): String {
        if (url.isBlank()) return ""
        return if (forceProxy || needsProxy(url)) getProxiedUrl(url) else url
    }

    // ─── Optimized URL generation ────────────────────────────────────────────

    fun getOptimizedImageUrl(
        url: String,
        context: Context,
        width: Int? = null,
        quality: Float? = null
    ): String {
        if (url.isBlank() || url.startsWith("data:") || url.contains("wsrv.nl")) return url

        val lowBandwidth = isLowBandwidth(context)
        val w = width ?: if (lowBandwidth) 320 else 640
        val q = quality ?: getOptimalQuality(context)

        return "https://wsrv.nl/?url=${java.net.URLEncoder.encode(url, "UTF-8")}&w=$w&q=${(q * 100).roundToInt()}&output=webp"
    }

    fun getOptimizedAvatarUrl(url: String?, context: Context, size: Int = 48): String? {
        if (url == null) return null
        val lowBandwidth = isLowBandwidth(context)
        val optimizedSize = if (lowBandwidth) min(size, 64) else size
        return getOptimizedImageUrl(url, context, width = optimizedSize * 2, quality = 0.8f)
    }
}
