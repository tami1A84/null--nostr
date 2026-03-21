package io.nurunuru.app.ui.miniapps

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.*
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// NIP-07 window.nostr bridge injected into every page.
// Promises are resolved/rejected via window.__nostr_resolve/__nostr_reject.
private val NOSTR_BRIDGE_JS = """
(function() {
  if (window.nostr) return;
  var _cb = {};
  window.__nostr_resolve = function(id, result) {
    if (_cb[id]) { _cb[id].resolve(result); delete _cb[id]; }
  };
  window.__nostr_reject = function(id, error) {
    if (_cb[id]) { _cb[id].reject(new Error(String(error))); delete _cb[id]; }
  };
  function _req(method, args) {
    return new Promise(function(resolve, reject) {
      var id = Math.random().toString(36).substr(2,9) + String(Date.now());
      _cb[id] = { resolve: resolve, reject: reject };
      if (!window.__bridge || !window.__bridge[method]) {
        reject(new Error('bridge not ready')); return;
      }
      if (args.length === 0) window.__bridge[method](id);
      else if (args.length === 1) window.__bridge[method](id, args[0]);
      else if (args.length === 2) window.__bridge[method](id, args[0], args[1]);
    });
  }
  window.nostr = {
    getPublicKey: function() { return _req('getPublicKey', []); },
    signEvent: function(event) { return _req('signEvent', [JSON.stringify(event)]); },
    getRelays: function() { return _req('getRelays', []); },
    nip04: {
      encrypt: function(pubkey, pt) { return _req('nip04Encrypt', [pubkey, pt]); },
      decrypt: function(pubkey, ct) { return _req('nip04Decrypt', [pubkey, ct]); }
    }
  };
})();
""".trimIndent()

private data class PermissionRequest(
    val kind: Int,
    val description: String,
    val onDecision: (Boolean) -> Unit
)

private val AUTO_APPROVED_KINDS = setOf(1, 6, 7, 9734, 1984, 30023, 30024, 30078)

private val KIND_LABELS = mapOf(
    0     to "プロフィール更新 (Kind 0)",
    3     to "フォローリスト更新 (Kind 3)",
    4     to "ダイレクトメッセージ (Kind 4)",
    10000 to "ミュートリスト更新 (Kind 10000)",
    10002 to "リレーリスト更新 (Kind 10002)"
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NostrBrowserApp(
    appName: String,
    pubkey: String,
    repository: NostrRepository,
    initialUrl: String,
    onBack: () -> Unit
) {
    val nuruColors = LocalNuruColors.current
    val scope = rememberCoroutineScope()

    val webViewState = remember { mutableStateOf<WebView?>(null) }
    val permissionState = remember { mutableStateOf<PermissionRequest?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var fileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        fileChooserCallback?.onReceiveValue(uris.toTypedArray())
        fileChooserCallback = null
    }

    fun navigateBack() {
        val wv = webViewState.value
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    BackHandler { navigateBack() }

    val bridge = remember {
        NostrJsBridge(
            pubkey = pubkey,
            scope = scope,
            webViewState = webViewState,
            permissionState = permissionState,
            repository = repository
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewState.value?.destroy()
            webViewState.value = null
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        // ── Header ───────────────────────────────────────────────────────────
        Surface(
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { navigateBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                }
                Text(
                    appName,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.Close, contentDescription = "閉じる")
                }
            }
        }

        HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)

        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = LineGreen,
                trackColor = Color.Transparent
            )
        }

        // ── WebView ──────────────────────────────────────────────────────────
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        allowFileAccess = false
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }
                    addJavascriptInterface(bridge, "__bridge")

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            isLoading = true
                            view.evaluateJavascript(NOSTR_BRIDGE_JS, null)
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            isLoading = false
                            view.evaluateJavascript(NOSTR_BRIDGE_JS, null)
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(
                            webView: WebView,
                            filePathCallback: ValueCallback<Array<Uri>>,
                            fileChooserParams: FileChooserParams
                        ): Boolean {
                            fileChooserCallback?.onReceiveValue(null)
                            fileChooserCallback = filePathCallback
                            val acceptTypes = fileChooserParams.acceptTypes
                            val mime = acceptTypes
                                ?.firstOrNull { it.isNotBlank() }
                                ?: "*/*"
                            filePickerLauncher.launch(mime)
                            return true
                        }
                    }

                    loadUrl(initialUrl)
                    webViewState.value = this
                }
            },
            modifier = Modifier.weight(1f)
        )
    }

    // ── Permission dialog ─────────────────────────────────────────────────────
    permissionState.value?.let { req ->
        AlertDialog(
            onDismissRequest = {
                req.onDecision(false)
                permissionState.value = null
            },
            title = { Text("署名リクエスト", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Webサイトから以下の操作のリクエストがあります")
                    Surface(
                        color = nuruColors.bgTertiary,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            req.description,
                            modifier = Modifier.padding(12.dp),
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        "秘密鍵はWebサイトには公開されません",
                        style = MaterialTheme.typography.bodySmall,
                        color = nuruColors.textTertiary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        req.onDecision(true)
                        permissionState.value = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LineGreen)
                ) { Text("承認") }
            },
            dismissButton = {
                TextButton(onClick = {
                    req.onDecision(false)
                    permissionState.value = null
                }) { Text("拒否") }
            }
        )
    }
}

// ── JavaScript Bridge ─────────────────────────────────────────────────────────

private class NostrJsBridge(
    private val pubkey: String,
    private val scope: CoroutineScope,
    private val webViewState: MutableState<WebView?>,
    private val permissionState: MutableState<PermissionRequest?>,
    private val repository: NostrRepository
) {
    @JavascriptInterface
    fun getPublicKey(requestId: String) {
        resolve(requestId, Json.encodeToString(pubkey))
    }

    @JavascriptInterface
    fun signEvent(requestId: String, eventJson: String) {
        scope.launch {
            val kind = try {
                org.json.JSONObject(eventJson).getInt("kind")
            } catch (_: Exception) { 1 }

            val approved = if (kind in AUTO_APPROVED_KINDS) {
                true
            } else {
                val deferred = CompletableDeferred<Boolean>()
                withContext(Dispatchers.Main) {
                    permissionState.value = PermissionRequest(
                        kind = kind,
                        description = KIND_LABELS[kind] ?: "イベント署名 (Kind $kind)",
                        onDecision = { deferred.complete(it) }
                    )
                }
                deferred.await()
            }

            if (approved) {
                val signed = repository.signEventForWebBridge(eventJson)
                if (signed != null) resolve(requestId, signed)
                else reject(requestId, "署名に失敗しました")
            } else {
                reject(requestId, "ユーザーが拒否しました")
            }
        }
    }

    @JavascriptInterface
    fun getRelays(requestId: String) {
        resolve(requestId, repository.getRelaysJson())
    }

    @JavascriptInterface
    fun nip04Encrypt(requestId: String, receiverPubkey: String, plaintext: String) {
        scope.launch {
            val result = repository.nip04EncryptForBridge(receiverPubkey, plaintext)
            if (result != null) resolve(requestId, Json.encodeToString(result))
            else reject(requestId, "暗号化に失敗しました")
        }
    }

    @JavascriptInterface
    fun nip04Decrypt(requestId: String, senderPubkey: String, ciphertext: String) {
        scope.launch {
            val result = repository.nip04DecryptForBridge(senderPubkey, ciphertext)
            if (result != null) resolve(requestId, Json.encodeToString(result))
            else reject(requestId, "復号に失敗しました")
        }
    }

    private fun resolve(id: String, jsonValue: String) {
        val safeId = id
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("`", "\\`")
            .replace("\u0000", "")
        webViewState.value?.post {
            webViewState.value?.evaluateJavascript(
                "window.__nostr_resolve('$safeId', $jsonValue)", null
            )
        }
    }

    private fun reject(id: String, message: String) {
        val safeId = id
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("`", "\\`")
            .replace("\u0000", "")
        val safeMsg = message.replace("\\", "\\\\").replace("'", "\\'")
        webViewState.value?.post {
            webViewState.value?.evaluateJavascript(
                "window.__nostr_reject('$safeId', '$safeMsg')", null
            )
        }
    }
}
