package com.packforge.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.packforge.app.domain.model.OperationProgress
import com.packforge.app.ui.components.AddonSite
import com.packforge.app.ui.components.SiteSelector
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebBrowserScreen(
    title: String,
    currentUrl: String,
    initialUrl: String,
    currentSite: AddonSite,
    importError: String?,
    isImporting: Boolean,
    importProgress: OperationProgress,
    webImportSuccess: Long?,
    onBack: () -> Unit,
    onSiteSelect: (AddonSite) -> Unit,
    onUrlChanged: (String) -> Unit,
    // (downloadUrl, pageUrl, siteName) -> Importa desde el WebView guardando el origen.
    onImportFromUrl: (String, String, String) -> Unit,
    onClearError: () -> Unit,
    webView: WebView
) {
    val context = LocalContext.current
    var isLoadingPage by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var showSuccess by remember { mutableStateOf(false) }

    LaunchedEffect(webImportSuccess) {
        if (webImportSuccess != null) {
            showSuccess = true
            delay(2600)
            showSuccess = false
        }
    }

    BackHandler(enabled = true) {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onBack()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            // Top bar integrada como flotante compacta sobre el contenido
            // para maximizar el espacio del WebView
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        webViewRef?.let { wv ->
                            if (initialUrl.isNotEmpty() && wv.url != initialUrl) {
                                wv.loadUrl(initialUrl)
                            } else {
                                wv.reload()
                            }
                        }
                    }) {
                        Icon(Icons.Default.Refresh, "Recargar Inicio")
                    }
                    IconButton(onClick = {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, webViewRef?.url?.toUri())
                        context.startActivity(intent)
                    }) {
                        Icon(Icons.Default.OpenInBrowser, "Navegador Externo")
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {

            BoxWithConstraints {
                if (maxWidth < 600.dp) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(padding)
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        SiteSelector(
                            currentSite = currentSite,
                            onSelect = { site ->
                                if (site != currentSite) onSiteSelect(site)
                            }
                        )
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(padding)
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        LandscapeSiteSelector(
                            currentSite = currentSite,
                            onSelect = onSiteSelect
                        )
                    }
                }
            }

            if (isLoadingPage) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            AnimatedContent(
                targetState = when {
                    webImportSuccess != null && showSuccess -> "success"
                    importProgress is OperationProgress.Loading -> "loading"
                    else -> "idle"
                },
                transitionSpec = {
                    (fadeIn(animationSpec = tween(300)) +
                     scaleIn(initialScale = 0.9f, animationSpec = tween(300)))
                        .togetherWith(fadeOut(animationSpec = tween(200)) +
                                      scaleOut(targetScale = 0.9f, animationSpec = tween(200)))
                },
                label = "webImportStatus"
            ) { state ->
                when (state) {
                    "loading" -> WebImportProgressBar(importProgress as? OperationProgress.Loading)
                    "success" -> WebImportSuccessChip()
                }
            }

            if (importError != null) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = importError, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = onClearError) { Icon(Icons.Default.Close, null) }
                    }
                }
            }

            key(currentSite.sourceKey) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    factory = { ctx ->
                        webViewRef = webView
                        webView
                    },
                update = { wv ->
                    if (wv.tag == null) {
                        wv.tag = "configured"
                        wv.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            // Se activa: MCPEDL y CurseForge abren sus menús
                            // laterales/de pantalla completa con window.open().
                            // Sin esto el toque en el icono de tres rayitas no
                            // hacía nada y la web quedaba congelada esperando
                            // una ventana que nunca se creaba.
                            javaScriptCanOpenWindowsAutomatically = true
                            setSupportMultipleWindows(true)
                            loadsImagesAutomatically = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            // Zoom y viewport móvil: los menús de estos sitios
                            // dependen de un ancho de ventana real, y con
                            // setLoadWithOverviewMode sin `wideViewPort` el
                            // layout se calculaba para escritorio y el menú
                            // aparecía fuera de pantalla.
                            setSupportZoom(true)
                            builtInZoomControls = false
                            displayZoomControls = false
                            // User-Agent de navegador móvil real: algunos sitios
                            // sirven una variante sin menú si detectan WebView.
                            userAgentString = wv.settings.userAgentString
                                ?.replace("; wv", "")
                                ?.replace(Regex("Version/\\d+\\.\\d+ "), "")
                        }
                        // Cookies habilitadas: sin esto, los sitios que guardan
                        // el estado del menú (abierto/cerrado) en sesión lo
                        // recargan en bucle al tocarlo.
                        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                        android.webkit.CookieManager.getInstance()
                            .setAcceptThirdPartyCookies(wv, true)
                        wv.webChromeClient = object : WebChromeClient() {
                            /**
                             * Menús que la web abre como ventana nueva.
                             *
                             * ⚠️ POR QUÉ NO SE DEVUELVE UN WEBVIEW AL SISTEMA
                             * (`WebViewTransport`): intentarlo dejaba la página
                             * COMPLETAMENTE EN BLANCO. Estos sitios usan
                             * `window.open()` no solo para el menú, sino también
                             * como paso intermedio de la descarga; al entregar
                             * ese popup al transporte, el WebView principal
                             * perdía su superficie y quedaba inutilizable.
                             *
                             * En su lugar se extrae la URL de destino y se
                             * decide en el WebView principal: si es una descarga
                             * se importa, y si es una página del menú se navega
                             * en la propia vista. Así el menú funciona y la
                             * página nunca queda en blanco.
                             */
                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: android.os.Message?
                            ): Boolean {
                                val host = view ?: return false
                                // WebView auxiliar SOLO para capturar la URL que
                                // la web pretendía abrir; no se muestra ni se
                                // entrega al sistema.
                                val probe = WebView(host.context)
                                probe.settings.javaScriptEnabled = true
                                probe.webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        childView: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val url = request?.url?.toString() ?: return false
                                        if (isAddonDownloadUrl(url)) {
                                            onImportFromUrl(
                                                url,
                                                webViewRef?.url ?: currentUrl,
                                                currentSite.sourceKey
                                            )
                                        } else if (url.startsWith("http")) {
                                            host.loadUrl(url)
                                        }
                                        childView?.destroy()
                                        return true
                                    }
                                }
                                (resultMsg?.obj as? android.webkit.WebView.WebViewTransport)
                                    ?.webView = probe
                                resultMsg?.sendToTarget()
                                return true
                            }
                        }
                        wv.webViewClient = object : WebViewClient() {
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
                                if (r?.isForMainFrame != true) return false
                                val url = r.url?.toString() ?: return false
                                val scheme = r.url?.scheme?.lowercase()

                                if (scheme != null && scheme != "http" && scheme != "https" &&
                                    scheme != "about" && scheme != "javascript") {
                                    try {
                                        context.startActivity(
                                            android.content.Intent(android.content.Intent.ACTION_VIEW, r.url)
                                        )
                                    } catch (e: Exception) {}
                                    return true
                                }

                                if (isAddonDownloadUrl(url)) {
                                    onImportFromUrl(url, webViewRef?.url ?: currentUrl, currentSite.sourceKey)
                                    return true
                                }
                                return false
                            }
                        }
                        wv.setDownloadListener { url, _, _, _, _ ->
                            if (url != null) onImportFromUrl(url, webViewRef?.url ?: currentUrl, currentSite.sourceKey)
                        }
                        if (wv.url.isNullOrEmpty()) {
                            wv.loadUrl(currentUrl.ifEmpty { initialUrl })
                        }
                    }

                    webViewRef = wv
                    try {
                        wv.onResume()
                    } catch (e: Exception) {}
                    wv.invalidate()
                }
            )

            // NOTA: no se dibuja ningún WebView superpuesto. El menú de estos
            // sitios se resuelve navegando en esta misma vista (ver
            // `onCreateWindow`): entregar un popup al sistema dejaba la página
            // en blanco, que es justo el fallo que se está corrigiendo.
        }
    }
}
}