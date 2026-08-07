package com.packforge.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.packforge.app.domain.model.OperationProgress

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebBrowserScreen(
    title: String,
    currentUrl: String,
    importError: String?,
    isImporting: Boolean,
    importProgress: OperationProgress,
    onBack: () -> Unit,
    onUrlChanged: (String) -> Unit,
    onImportFromUrl: (String) -> Unit,
    onClearError: () -> Unit,
    onReloadInitial: () -> Unit,
    webView: WebView
) {
    val context = LocalContext.current
    var isLoadingPage by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Configurar/actualizar listeners del WebView persistente cada vez que el
    // composable entra en composición. El WebView persiste entre pestañas (estado
    // conservado → NO pantalla en blanco), pero los callbacks deben capturar el
    // estado de la composición ACTUAL (isLoadingPage / onUrlChanged).
    DisposableEffect(Unit) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(false)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView?, u: String?, f: Bitmap?) {
                isLoadingPage = true
            }
            override fun onPageFinished(v: WebView?, u: String?) {
                isLoadingPage = false
                if (u != null && u != "about:blank") {
                    onUrlChanged(u)
                }
            }
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                val url = r?.url?.toString() ?: return false
                if (isAddonDownloadUrl(url)) {
                    onImportFromUrl(url)
                    return true
                }
                return false
            }
        }
        webView.setDownloadListener { url, _, _, _, _ ->
            if (url != null) onImportFromUrl(url)
        }
        onDispose { /* NO destruir: el WebView es persistente entre pestañas */ }
    }

    // Sincronizar el WebView con la URL actual solo si difiere de la que está cargada
    LaunchedEffect(currentUrl) {
        val wv = webView
        if (wv.url != currentUrl || wv.url.isNullOrEmpty()) {
            wv.loadUrl(currentUrl)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás")
                    }
                },
                actions = {
                    IconButton(onClick = onReloadInitial) {
                        Icon(Icons.Default.Refresh, "Recargar Inicio")
                    }
                    IconButton(onClick = {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, webViewRef?.url?.toUri())
                        context.startActivity(intent)
                    }) {
                        Icon(Icons.Default.OpenInBrowser, "Navegador Externo")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            
            if (isLoadingPage) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            // Panel de Importación
            if (isImporting && importProgress is OperationProgress.Loading) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), progress = { importProgress.progress ?: 0f }, strokeCap = StrokeCap.Round)
                        Text(text = importProgress.message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Errores
            if (importError != null) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = importError, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = onClearError) { Icon(Icons.Default.Close, null) }
                    }
                }
            }

            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    // REUTILIZAR el WebView persistente en lugar de crear uno nuevo.
                    // Esto conserva historial/scroll/estado → NO pantalla en blanco.
                    webViewRef = webView
                    webView
                },
                update = { wv ->
                    webViewRef = wv
                }
            )
        }
    }
}

private fun isAddonDownloadUrl(url: String): Boolean {
    val lower = url.lowercase()
    return lower.endsWith(".mcaddon") || lower.endsWith(".mcpack") || 
           lower.contains(".mcaddon?") || lower.contains(".mcpack?")
}
