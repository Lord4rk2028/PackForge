package com.packforge.app.ui.screens

import com.packforge.app.R
import androidx.compose.ui.res.stringResource
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.packforge.app.domain.model.OperationProgress

import java.net.URLEncoder

private const val MCPEDL_HOME = "https://mcpedl.com/category/mods-addons/"

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun McpedlSearchScreen(
    importError: String?,
    isImporting: Boolean,
    importProgress: OperationProgress,
    onBack: () -> Unit,
    onImportFromUrl: (String, String, String) -> Unit,
    onClearError: () -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var isLoadingPage by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val onImportFromUrlState by rememberUpdatedState(onImportFromUrl)

    fun buildSearchUrl(searchQuery: String): String {
        val trimmed = searchQuery.trim()
        return if (trimmed.isEmpty()) {
            MCPEDL_HOME
        } else {
            val encoded = URLEncoder.encode(trimmed, Charsets.UTF_8.name())
            "https://mcpedl.com/?s=$encoded"
        }
    }

    fun loadSearch(searchQuery: String) {
        webViewRef?.loadUrl(buildSearchUrl(searchQuery))
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    Scaffold(
        topBar = {
            // Barra compacta flotante integrada
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                    Text(
                        "Buscar en MCPEDL",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_reload))
                    }
                    IconButton(
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                webViewRef?.url?.toUri()
                                    ?: MCPEDL_HOME.toUri()
                            )
                            context.startActivity(intent)
                        }
                    ) {
                        Icon(
                            Icons.Default.OpenInBrowser,
                            contentDescription = stringResource(R.string.cd_open_browser)
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Addons oficiales para Minecraft Bedrock. Busca, abre un " +
                    "proyecto y pulsa Descargar; PackForge lo importarÃ¡ automÃ¡ticamente.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isImporting,
                placeholder = { Text(stringResource(R.string.mcpedl_search_hint)) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    Row {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_clear))
                            }
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { loadSearch(query) }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )

            if (isLoadingPage) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (isImporting && importProgress is OperationProgress.Loading) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            progress = { importProgress.progress ?: 0f },
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                            strokeWidth = 3.dp
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = importProgress.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            importProgress.progress?.let { p ->
                                LinearProgressIndicator(
                                    progress = { p },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp)
                                        .height(2.dp),
                                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                                )
                            }
                        }
                    }
                }
            }

            if (importError != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = importError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onClearError) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.common_close),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadsImagesAutomatically = true
                        // Mismo arreglo que en el navegador principal: MCPEDL
                        // abre su menú con window.open(), y sin estas dos
                        // opciones el toque en el icono de tres rayitas no hacía
                        // nada y la web quedaba congelada.
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.setSupportMultipleWindows(true)
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        // User-Agent sin la marca "wv": algunos sitios sirven una
                        // variante sin menú si detectan un WebView.
                        settings.userAgentString = settings.userAgentString
                            ?.replace("; wv", "")
                            ?.replace(Regex("Version/\\d+\\.\\d+ "), "")
                        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                        android.webkit.CookieManager.getInstance()
                            .setAcceptThirdPartyCookies(this, true)

                        // Menú emergente de la web (window.open): se captura la URL con un
                        // WebView auxiliar que NO se muestra ni se entrega al
                        // sistema. Entregar un popup real dejaba la página en
                        // blanco, porque estos sitios usan `window.open()` también
                        // como paso intermedio de la descarga.
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: android.os.Message?
                            ): Boolean {
                                val host = view ?: return false
                                val probe = WebView(host.context)
                                probe.settings.javaScriptEnabled = true
                                probe.webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        childView: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val url = request?.url?.toString() ?: return false
                                        if (isAddonDownloadUrl(url)) {
                                            onImportFromUrlState(
                                                url,
                                                webViewRef?.url ?: MCPEDL_HOME,
                                                "MCPEDL"
                                            )
                                        } else if (url.startsWith("http")) {
                                            host.loadUrl(url)
                                        }
                                        childView?.destroy()
                                        return true
                                    }
                                }
                                (resultMsg?.obj as? WebView.WebViewTransport)?.webView = probe
                                resultMsg?.sendToTarget()
                                return true
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: Bitmap?
                            ) {
                                isLoadingPage = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoadingPage = false
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false
                                if (isAddonDownloadUrl(url)) {
                                    onImportFromUrlState(url, webViewRef?.url ?: MCPEDL_HOME, "MCPEDL")
                                    return true
                                }
                                return false
                            }
                        }

                        setDownloadListener { url, _, _, mimetype, _ ->
                            if (url != null && (
                                    isAddonDownloadUrl(url) ||
                                        isAddonMimeType(mimetype)
                                    )
                            ) {
                                onImportFromUrlState(url, webViewRef?.url ?: MCPEDL_HOME, "MCPEDL")
                            }
                        }

                        webViewRef = this
                        loadUrl(buildSearchUrl(query))
                    }
                },
                update = { webView ->
                    webViewRef = webView
                }
            )

            // Menú emergente superpuesto: se cierra al tocar fuera, igual que
            // en el navegador principal.
            // Sin WebView superpuesto: el menú de MCPEDL se resuelve navegando
            // en esta misma vista (ver `onCreateWindow`). Entregar un popup al
            // sistema dejaba la página en blanco.
        }
    }
}

private fun isAddonMimeType(mimetype: String?): Boolean {
    if (mimetype == null) return false
    val lower = mimetype.lowercase()
    return lower.contains("zip") ||
        lower.contains("octet-stream") ||
        lower.contains("application/x-zip")
}

