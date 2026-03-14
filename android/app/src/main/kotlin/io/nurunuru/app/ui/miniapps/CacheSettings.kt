package io.nurunuru.app.ui.miniapps

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.prefs.AppPreferences
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ── キャッシュタイプ定義 ──────────────────────────────────────────────────────

private data class CacheTypeConfig(
    val id: String,
    val name: String,
    val kinds: String,
    val defaultTtlMs: Long
)

private val TTL_PRESETS = listOf(
    "1時間"  to 3_600_000L,
    "6時間"  to 21_600_000L,
    "12時間" to 43_200_000L,
    "1日"   to 86_400_000L,
    "3日"   to 259_200_000L,
    "7日"   to 604_800_000L,
    "30日"  to 2_592_000_000L
)

private val CACHE_TYPES = listOf(
    CacheTypeConfig("profile",      "プロフィール",         "kind 0",             86_400_000L),
    CacheTypeConfig("timeline",     "タイムライン",         "kind 1, 30023",      86_400_000L),
    CacheTypeConfig("followlist",   "フォローリスト",       "kind 3",             86_400_000L),
    CacheTypeConfig("mutelist",     "ミュートリスト",       "kind 10000",         86_400_000L),
    CacheTypeConfig("notification", "通知",                 "kind 6, 7, 8, 9735", 86_400_000L),
    CacheTypeConfig("emoji",        "絵文字",               "kind 10030",         86_400_000L),
    CacheTypeConfig("relay",        "リレーリスト",         "kind 10002",         86_400_000L),
    CacheTypeConfig("badge",        "プロフィールバッジ",   "kind 8, 30009",      86_400_000L),
)

private fun formatTtl(ms: Long): String {
    val preset = TTL_PRESETS.find { it.second == ms }
    if (preset != null) return preset.first
    return when {
        ms < 3_600_000L  -> "${ms / 60_000L}分"
        ms < 86_400_000L -> "${ms / 3_600_000L}時間"
        else             -> "${ms / 86_400_000L}日"
    }
}

private fun ndbFileSize(context: Context): Long =
    try { File("${context.filesDir.absolutePath}/nostrdb_ndb").let {
        if (it.isDirectory) it.walkTopDown().sumOf { f -> if (f.isFile) f.length() else 0L }
        else it.length()
    } } catch (_: Exception) { 0L }

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024             -> "${bytes} B"
    bytes < 1024 * 1024      -> "${"%.1f".format(bytes / 1024.0)} KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
    else                     -> "${"%.1f".format(bytes / (1024.0 * 1024 * 1024))} GB"
}

// ── メイン UI ────────────────────────────────────────────────────────────────

@Composable
fun CacheSettings(prefs: AppPreferences, repository: NostrRepository) {
    val nuruColors = LocalNuruColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var stats by remember { mutableStateOf(repository.getCacheStats()) }
    var ndbSize by remember { mutableStateOf(0L) }
    var clearing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        ndbSize = withContext(Dispatchers.IO) { ndbFileSize(context) }
    }

    fun refreshStats() {
        stats = repository.getCacheStats()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // ── 統計カード ────────────────────────────────────────────────────
        item {
            Surface(
                color = nuruColors.bgSecondary,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("現在のキャッシュ", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${stats.entryCount}", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = LineGreen)
                            Text("アプリキャッシュ件数", fontSize = 10.sp, color = nuruColors.textTertiary)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${stats.memoryProfileCount}", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = LineGreen)
                            Text("プロフィール(メモリ)", fontSize = 10.sp, color = nuruColors.textTertiary)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(formatBytes(ndbSize), fontWeight = FontWeight.Bold, fontSize = 22.sp, color = LineGreen)
                            Text("nostrdb", fontSize = 10.sp, color = nuruColors.textTertiary)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                clearing = true
                                scope.launch(Dispatchers.IO) {
                                    repository.clearExpiredCache()
                                    withContext(Dispatchers.Main) {
                                        refreshStats()
                                        clearing = false
                                    }
                                }
                            },
                            enabled = !clearing,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("期限切れを削除", fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                clearing = true
                                scope.launch(Dispatchers.IO) {
                                    repository.clearAllCache()
                                    withContext(Dispatchers.Main) {
                                        refreshStats()
                                        clearing = false
                                    }
                                }
                            },
                            enabled = !clearing,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("全てクリア", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // ── セクションヘッダー ────────────────────────────────────────────
        item {
            Text("キャッシュ設定", fontWeight = FontWeight.Bold, fontSize = 15.sp,
                modifier = Modifier.padding(top = 4.dp))
            Text(
                "種類ごとにキャッシュの有効/無効と保持期間を設定できます。",
                fontSize = 12.sp, color = nuruColors.textTertiary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        // ── 各キャッシュタイプ行 ──────────────────────────────────────────
        items(CACHE_TYPES) { cfg ->
            var enabled by remember { mutableStateOf(prefs.isCacheEnabled(cfg.id)) }
            var selectedTtl by remember { mutableStateOf(prefs.getCacheTtlMs(cfg.id, cfg.defaultTtlMs)) }
            var count by remember { mutableStateOf(repository.getCacheEntriesCount(cfg.id)) }
            var showTtlMenu by remember { mutableStateOf(false) }

            Surface(
                color = nuruColors.bgSecondary,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 上段：名前 + 有効トグル
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cfg.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text(cfg.kinds, fontSize = 11.sp, color = nuruColors.textTertiary)
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { v ->
                                enabled = v
                                prefs.setCacheEnabled(cfg.id, v)
                                repository.applyCacheSettings()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = LineGreen
                            )
                        )
                    }

                    // 下段：TTL選択 + 件数 + クリアボタン（有効時のみ）
                    if (enabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // TTL チップ選択
                            Box {
                                OutlinedButton(
                                    onClick = { showTtlMenu = true },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text(formatTtl(selectedTtl), fontSize = 12.sp)
                                }
                                DropdownMenu(
                                    expanded = showTtlMenu,
                                    onDismissRequest = { showTtlMenu = false }
                                ) {
                                    TTL_PRESETS.forEach { (label, ms) ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    label,
                                                    color = if (ms == selectedTtl) LineGreen
                                                            else nuruColors.textPrimary,
                                                    fontWeight = if (ms == selectedTtl) FontWeight.Bold
                                                                 else FontWeight.Normal
                                                )
                                            },
                                            onClick = {
                                                selectedTtl = ms
                                                prefs.setCacheTtlMs(cfg.id, ms)
                                                repository.applyCacheSettings()
                                                showTtlMenu = false
                                            }
                                        )
                                    }
                                }
                            }

                            // 件数 + クリアボタン
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (count > 0) {
                                    Text("${count}件", fontSize = 11.sp, color = nuruColors.textTertiary)
                                }
                                IconButton(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            repository.clearCacheByType(cfg.id)
                                            withContext(Dispatchers.Main) {
                                                count = 0
                                                refreshStats()
                                            }
                                        }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete, null,
                                        tint = nuruColors.textTertiary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
