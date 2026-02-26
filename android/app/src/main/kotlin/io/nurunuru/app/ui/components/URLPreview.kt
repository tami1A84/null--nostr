package io.nurunuru.app.ui.components

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL

@Serializable
data class PreviewData(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val image: String? = null,
    val siteName: String? = null,
    val favicon: String? = null
)

private val ogCache = mutableMapOf<String, PreviewData?>()
private val okHttpClient = OkHttpClient()
private val json = Json { ignoreUnknownKeys = true }

@Composable
fun URLPreview(url: String, compact: Boolean = false) {
    val context = LocalContext.current
    var data by remember { mutableStateOf<PreviewData?>(ogCache[url]) }
    var isLoading by remember { mutableStateOf(data == null && !ogCache.containsKey(url)) }

    LaunchedEffect(url) {
        if (ogCache.containsKey(url)) {
            data = ogCache[url]
            isLoading = false
            return@LaunchedEffect
        }

        if (!isValidUrl(url) || isMediaUrl(url)) {
            ogCache[url] = null
            isLoading = false
            return@LaunchedEffect
        }

        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://api.microlink.io?url=${run { java.net.URLEncoder.encode(url, "UTF-8") }}")
                    .build()

                val response = okHttpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val root = json.parseToJsonElement(body).jsonObject
                        if (root["status"]?.jsonPrimitive?.content == "success") {
                            val d = root["data"]?.jsonObject
                            if (d != null) {
                                val preview = PreviewData(
                                    url = url,
                                    title = d["title"]?.jsonPrimitive?.content,
                                    description = d["description"]?.jsonPrimitive?.content,
                                    image = d["image"]?.jsonObject?.get("url")?.jsonPrimitive?.content,
                                    siteName = d["publisher"]?.jsonPrimitive?.content,
                                    favicon = d["logo"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                                )
                                if (preview.title != null) {
                                    ogCache[url] = preview
                                    withContext(Dispatchers.Main) {
                                        data = preview
                                    }
                                } else {
                                    ogCache[url] = null
                                }
                            }
                        }
                    }
                } else {
                    ogCache[url] = null
                }
            } catch (e: Exception) {
                Log.w("URLPreview", "Failed to fetch OG data for $url", e)
                ogCache[url] = null
            } finally {
                withContext(Dispatchers.Main) {
                    isLoading = false
                }
            }
        }
    }

    if (isLoading) {
        URLPreviewSkeleton(compact = compact)
    } else if (data != null) {
        URLPreviewCard(data = data!!, compact = compact)
    }
}

@Composable
private fun URLPreviewCard(data: PreviewData, compact: Boolean) {
    val context = LocalContext.current
    val displayDomain = try { URL(data.url).host.removePrefix("www.") } catch (e: Exception) { "" }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(data.url))
                    context.startActivity(intent)
                } catch (e: Exception) { }
            },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        if (compact) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (data.image != null) {
                    AsyncImage(
                        model = data.image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
                    )
                } else if (data.favicon != null) {
                    Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                        AsyncImage(model = data.favicon, contentDescription = null, modifier = Modifier.size(24.dp))
                    }
                } else {
                    Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Link, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = data.title ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = displayDomain,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        } else {
            Column {
                if (data.image != null) {
                    AsyncImage(
                        model = data.image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(180.dp)
                    )
                }
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (data.favicon != null) {
                            AsyncImage(model = data.favicon, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                        Text(
                            text = data.siteName ?: displayDomain,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = data.title ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (data.description != null) {
                        Text(
                            text = data.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun URLPreviewSkeleton(compact: Boolean) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        if (compact) {
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Skeleton(modifier = Modifier.size(64.dp), shape = RoundedCornerShape(8.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Skeleton(modifier = Modifier.height(16.dp).fillMaxWidth(0.8f))
                    Skeleton(modifier = Modifier.height(12.dp).width(60.dp))
                }
            }
        } else {
            Column {
                Skeleton(modifier = Modifier.fillMaxWidth().height(180.dp), shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Skeleton(modifier = Modifier.height(12.dp).width(100.dp))
                    Skeleton(modifier = Modifier.height(16.dp).fillMaxWidth(0.8f))
                    Skeleton(modifier = Modifier.height(12.dp).fillMaxWidth())
                }
            }
        }
    }
}

private fun isValidUrl(url: String): Boolean = try {
    val u = URL(url)
    u.protocol in listOf("http", "https") && u.host.isNotEmpty()
} catch (e: Exception) { false }

private fun isMediaUrl(url: String): Boolean =
    url.matches(Regex(".*\\.(jpg|jpeg|png|gif|webp|svg|mp4|webm|mov)(\\?.*)?$", RegexOption.IGNORE_CASE))
