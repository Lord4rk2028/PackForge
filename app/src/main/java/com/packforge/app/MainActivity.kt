package com.packforge.app

import com.packforge.app.R
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.core.content.IntentCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavOptions
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import com.packforge.app.domain.model.OperationProgress
import com.packforge.app.ui.components.PackForgeTopBar
import com.packforge.app.ui.components.ExpressiveAlertDialog
import com.packforge.app.ui.navigation.Screen
import com.packforge.app.ui.navigation.getScreenFromRoute
import com.packforge.app.ui.screens.ConflictsScreen
import com.packforge.app.ui.screens.ExportSetupScreen
import com.packforge.app.ui.screens.ImportScreen
import com.packforge.app.ui.screens.MergeOverlay
import com.packforge.app.ui.screens.StudioScreen
import com.packforge.app.service.MergeSession
import com.packforge.app.ui.theme.PackForgeTheme
import com.packforge.app.ui.viewmodel.PackForgeEvent
import com.packforge.app.ui.viewmodel.PackForgeViewModel
import com.packforge.app.ui.viewmodel.ThemeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import com.packforge.app.ui.components.FloatingBottomBar
import com.packforge.app.ui.navigation.createEnterTransition
import com.packforge.app.ui.navigation.createExitTransition
import com.packforge.app.ui.navigation.getTransitionType
import com.packforge.app.ui.theme.LocalMotionLevel

class MainActivity : ComponentActivity() {

    /**
     * Contador que se incrementa cada vez que handleShareIntent detecta
     * URIs válidas. La UI (Composable) observa este valor para re-leer
     * SharedPreferences, de modo que cada share intent nuevo se procesa
     * correctamente (incluyendo los que llegan vía onNewIntent).
     */
    val shareTrigger = mutableIntStateOf(0)

    /** Android 13+ exige permiso runtime para mostrar la notificación de progreso. */
    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val perm = android.Manifest.permission.POST_NOTIFICATIONS
            if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(perm), 9001)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        handleShareIntent(intent)
        setContent {
            val themeViewModel: ThemeViewModel = viewModel()
            val prefs by themeViewModel.preferences.collectAsStateWithLifecycle()
            PackForgeTheme(prefs = prefs) {
                PackForgeApp(themeViewModel = themeViewModel)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: android.content.Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        val uris = mutableListOf<Uri>()
        when (action) {
            android.content.Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it }
                intent.data?.let { if (uris.isEmpty()) uris += it }
            }
            android.content.Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it }
            }
            android.content.Intent.ACTION_VIEW -> {
                intent.data?.let { uris += it }
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { if (uris.isEmpty()) uris += it }
            }
            else -> return
        }
        if (uris.isEmpty()) return

        // La validación abre streams y escanea el contenido del ZIP. Hacerlo
        // en el hilo principal (onCreate/onNewIntent) congelaba la arrancada
        // al abrir archivos desde "abrir con": ahora corre en IO y solo los
        // resultados vuelven a Main.
        lifecycleScope.launch(Dispatchers.IO) {
            // Validar archivos: .mcaddon/.mcpack siempre válidos, .zip solo si tiene estructura de addon
            val valid = uris.filter { uri ->
                val p = uri.toString().lowercase()
                val seg = uri.lastPathSegment?.lowercase() ?: ""

                // .mcaddon y .mcpack son siempre válidos
                if (p.endsWith(".mcaddon") || seg.endsWith(".mcaddon") ||
                    p.endsWith(".mcpack") || seg.endsWith(".mcpack")) {
                    return@filter true
                }

                // .zip: verificar si tiene estructura de addon (pack.json o manifest.json)
                // o contiene .mcpack/.mcaddon anidados (RP+BP separados dentro del .zip)
                if (p.endsWith(".zip") || seg.endsWith(".zip")) {
                    try {
                        contentResolver.openInputStream(uri)?.use { inputStream ->
                            java.util.zip.ZipInputStream(inputStream).use { zip ->
                                var entry = zip.nextEntry
                                while (entry != null) {
                                    val name = entry.name.lowercase()
                                    // pack.json o manifest.json en cualquier nivel
                                    if (name.endsWith("/pack.json") || name == "pack.json" ||
                                        name.endsWith("/manifest.json") || name == "manifest.json") {
                                        return@filter true
                                    }
                                    // .mcpack / .mcaddon anidados dentro del .zip (RP+BP separados)
                                    if (name.endsWith(".mcpack") || name.endsWith(".mcaddon")) {
                                        return@filter true
                                    }
                                    zip.closeEntry()
                                    entry = zip.nextEntry
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Si hay error al leer, rechazar
                    }
                    return@filter false
                }

                // Otros formatos: rechazar
                false
            }

            withContext(Dispatchers.Main) {
                // Si no hay ninguno válido, mostrar Toast (con delay para que la ventana esté lista)
                if (valid.isEmpty()) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        android.widget.Toast.makeText(
                            this@MainActivity, "Solo se aceptan archivos .mcaddon y .mcpack", android.widget.Toast.LENGTH_LONG
                        ).show()
                    }, 600)
                    return@withContext
                }

                // Guardar en SharedPreferences para que el ViewModel los recoja
                val prefs = getSharedPreferences("pf_share_pending", MODE_PRIVATE)
                // NO limpiar: permitir acumulación si el usuario comparte rápido
                val current = prefs.getStringSet("uris", emptySet<String>())?.toMutableSet() ?: mutableSetOf()
                valid.map { it.toString() }.forEach { current.add(it) }
                prefs.edit().putStringSet("uris", current).apply()

                // Notificar a la UI que hay nuevos URIs pendientes
                shareTrigger.intValue++
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackForgeApp(
    packForgeViewModel: PackForgeViewModel = viewModel(),
    themeViewModel: ThemeViewModel = viewModel()
) {
    val appContext = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val navController = rememberNavController()

    // ─── ESTADO DEL SHELL ──────────────────────────────────────
    // Solo lo que la carcasa necesita de verdad: visibilidad de las barras y
    // el contador del badge. El resto de flows (addons, progreso, export…)
    // se colecta DENTRO de cada destino de navegación: aquí arriba, cada
    // tick de progreso de importación/exportación disparaba una
    // recomposición de Scaffold + NavHost + pantalla completa (tormenta de
    // recomposición raíz), que era la fuente del jank generalizado.
    val criticalCount by packForgeViewModel.criticalConflictsCount.collectAsStateWithLifecycle()
    val activeWebSource by packForgeViewModel.activeWebSource.collectAsStateWithLifecycle()
    val showMyModpacks by packForgeViewModel.showMyModpacks.collectAsStateWithLifecycle()
    val showThemeSettings by packForgeViewModel.showThemeSettings.collectAsStateWithLifecycle()

    // Variable para controlar si ya procesamos el share intent
    @Suppress("UNUSED_VARIABLE")
    var shareIntentProcessed by remember { mutableStateOf(false) }

    // URIs compartidos que resultaron ser modpacks de PackForge, esperando
    // decisión del usuario (importar como Addon o como Modpack a biblioteca).
    var pendingShareModpacks by remember { mutableStateOf<List<android.net.Uri>?>(null) }
    var pendingShareAddons by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }

    LaunchedEffect(Unit) {
        packForgeViewModel.events.collectLatest { event ->
            when (event) {
                is PackForgeEvent.ShowSnackbar -> {
                    android.widget.Toast.makeText(appContext, event.message, android.widget.Toast.LENGTH_SHORT).show()
                }
                PackForgeEvent.Vibration -> haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    // ─── SHARE INTENT ──────────────────────────────────────────
    val mainActivity = appContext as? MainActivity
    val shareTriggerValue = mainActivity?.shareTrigger?.value ?: 0

    LaunchedEffect(shareTriggerValue) {
        if (mainActivity == null || shareTriggerValue == 0) return@LaunchedEffect
        val prefs = mainActivity.getSharedPreferences("pf_share_pending", android.content.Context.MODE_PRIVATE)
        val stored = prefs.getStringSet("uris", null)
        if (!stored.isNullOrEmpty()) {
            // Limpiar SharedPreferences ANTES de importar para evitar re-procesamiento
            prefs.edit().remove("uris").apply()
            val uris = stored.map { android.net.Uri.parse(it) }

            // Detectar cuáles son modpacks de PackForge (contienen PackForge.ID)
            val modpacks = mutableListOf<android.net.Uri>()
            val nonModpacks = mutableListOf<android.net.Uri>()
            for (uri in uris) {
                if (packForgeViewModel.isPackForgeModpack(uri)) modpacks += uri else nonModpacks += uri
            }

            if (modpacks.isNotEmpty()) {
                // Mostrar diálogo: importar como Addon o como Modpack a biblioteca
                pendingShareModpacks = modpacks
                pendingShareAddons = nonModpacks
            } else {
                // No hay modpacks → importar directamente como addon
                packForgeViewModel.importAddons(appContext, nonModpacks)
                if (navController.currentDestination?.route != com.packforge.app.ui.navigation.Screen.Import.route) {
                    navController.navigate(com.packforge.app.ui.navigation.Screen.Import.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentScreen = getScreenFromRoute(currentRoute)

    // ── Orden de tabs para transición direction-aware (dock inferior) ──
    val screenOrder = listOf(
        Screen.Import.route, Screen.Conflicts.route,
        Screen.Export.route, Screen.Studio.route
    )
    fun routeIndex(route: String?) = screenOrder.indexOf(route).let { if (it == -1) 0 else it }


    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (activeWebSource == null && !showMyModpacks && !showThemeSettings) {
                PackForgeTopBar(
                    title = currentScreen.title,
                    titleContent = when (currentScreen) {
                        Screen.Import -> {
                            {
                                Image(
                                    painter = painterResource(id = R.drawable.title_importar),
                                    contentDescription = "IMPORTAR",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .height(32.dp)
                                        .wrapContentWidth(Alignment.Start)
                                )
                            }
                        }
                        Screen.Conflicts -> {
                            {
                                ConflictTitle(packForgeViewModel = packForgeViewModel)
                            }
                        }
                        Screen.Export -> {
                            {
                                Image(
                                    painter = painterResource(id = R.drawable.title_exportar),
                                    contentDescription = "EXPORTAR",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .height(32.dp)
                                        .wrapContentWidth(Alignment.Start)
                                )
                            }
                        }
                        Screen.Studio -> {
                            {
                                Image(
                                    painter = painterResource(id = R.drawable.title_estudio),
                                    contentDescription = "ESTUDIO",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .height(32.dp)
                                        .wrapContentWidth(Alignment.Start)
                                )
                            }
                        }
                    },
                    actions = {}
                )
            }
        },
        bottomBar = {
            if (activeWebSource == null && !showMyModpacks && !showThemeSettings) {
                // Detectar si hay exportación en proceso
                val mergeSession by MergeSession.state.collectAsStateWithLifecycle()
                val isExporting = !mergeSession.done && mergeSession.phase != "idle"

                FloatingBottomBar(
                    currentRoute = currentRoute,
                    criticalConflictsCount = criticalCount,
                    enabled = !isExporting,
                    onNavigate = { screen ->
                        if (isExporting) {
                            // Mostrar Toast indicando que no puede navegar durante la exportación
                            android.widget.Toast.makeText(
                                appContext,
                                "Espere a que termine la exportación",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // Sistema de transiciones optimizado: rápido, fluido y adaptativo
            val motionLevel = LocalMotionLevel.current

            // Memorizadas: sin `remember`, cada recomposición de la carcasa
            // creaba 4 lambdas nuevas y el NavHost (parámetro inestable)
            // re-ejecutaba TODOS los destinos aunque nada hubiera cambiado.
            // La dirección se deriva del orden de pestañas dentro de la
            // lambda, así que la misma pareja sirve para los 4 hooks.
            val navEnter: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition) =
                remember(motionLevel) {
                    {
                        val transitionType = getTransitionType(
                            initialState.destination.route,
                            targetState.destination.route
                        )
                        createEnterTransition(transitionType, motionLevel)
                    }
                }
            val navExit: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition) =
                remember(motionLevel) {
                    {
                        val transitionType = getTransitionType(
                            initialState.destination.route,
                            targetState.destination.route
                        )
                        createExitTransition(transitionType, motionLevel)
                    }
                }

            NavHost(
                navController = navController,
                startDestination = Screen.Import.route,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                enterTransition = navEnter,
                exitTransition = navExit,
                popEnterTransition = navEnter,
                popExitTransition = navExit
            ) {
                composable(Screen.Import.route) {
                    // Colectado AQUÍ: este destino se recompone solo, sin
                    // arrastrar al resto del árbol con cada tick de progreso.
                    val addons by packForgeViewModel.addons.collectAsStateWithLifecycle()
                    val conflicts by packForgeViewModel.conflicts.collectAsStateWithLifecycle()
                    val isImporting by packForgeViewModel.isImporting.collectAsStateWithLifecycle()
                    val importProgress by packForgeViewModel.importProgress.collectAsStateWithLifecycle()
                    val compatibilityScore by packForgeViewModel.compatibilityScore.collectAsStateWithLifecycle()
                    ImportScreen(
                        addons = addons, conflicts = conflicts, isImporting = isImporting,
                        importProgress = importProgress, compatibilityScore = compatibilityScore,
                        onImportUris = { packForgeViewModel.importAddons(appContext, it) },
                        onRemoveAddon = { packForgeViewModel.removeAddon(it) },
                        onToggleAddon = { packForgeViewModel.toggleAddon(it) },
                        onMoveAddon = { id, dir -> packForgeViewModel.moveAddon(id, dir) },
                        onClearAllAddons = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            packForgeViewModel.clearAll()
                        }
                    )
                }
                composable(Screen.Conflicts.route) {
                    val addons by packForgeViewModel.addons.collectAsStateWithLifecycle()
                    val conflicts by packForgeViewModel.conflicts.collectAsStateWithLifecycle()
                    val resolutions by packForgeViewModel.resolutions.collectAsStateWithLifecycle()
                    val conflictStrategy by packForgeViewModel.conflictStrategy.collectAsStateWithLifecycle()
                    val mergeConflicts by packForgeViewModel.mergeConflicts.collectAsStateWithLifecycle()
                    ConflictsScreen(
                        conflicts = conflicts, addons = addons, resolutions = resolutions,
                        onResolve = { cId, wId -> packForgeViewModel.resolveConflict(cId, wId) },
                        onDismiss = { packForgeViewModel.dismissConflict(it) },
                        conflictStrategy = conflictStrategy,
                        onConflictStrategyChange = { packForgeViewModel.setConflictStrategy(it) },
                        mergeConflicts = mergeConflicts,
                        onResolveMergeConflict = { id, resolution -> packForgeViewModel.resolveMergeConflict(id, resolution) }
                    )
                }
                composable(Screen.Export.route) {
                    val metadata by packForgeViewModel.metadata.collectAsStateWithLifecycle()
                    val addons by packForgeViewModel.addons.collectAsStateWithLifecycle()
                    val conflicts by packForgeViewModel.conflicts.collectAsStateWithLifecycle()
                    val resolutions by packForgeViewModel.resolutions.collectAsStateWithLifecycle()
                    val exportState by packForgeViewModel.exportState.collectAsStateWithLifecycle()
                    val isMinecraftInstalled by packForgeViewModel.isMinecraftInstalled.collectAsStateWithLifecycle()
                    val minecraftVersion by packForgeViewModel.minecraftVersion.collectAsStateWithLifecycle()
                    val minecraftUri by packForgeViewModel.minecraftUri.collectAsStateWithLifecycle()
                    ExportSetupScreen(
                        viewModel = packForgeViewModel,
                        metadata = metadata, addons = addons, conflicts = conflicts,
                        resolutions = resolutions, exportState = exportState,
                        isMinecraftInstalled = isMinecraftInstalled, minecraftVersion = minecraftVersion,
                        minecraftUri = minecraftUri,
                        onMetadataChange = { packForgeViewModel.updateMetadata(it) },
                        onExport = { uri, toMc -> packForgeViewModel.exportModpack(appContext, uri, toMc) },
                        onResetExport = { packForgeViewModel.resetExportState() },
                        onNavigateToImport = {
                            if (navController.currentDestination?.route != Screen.Import.route) {
                                navController.navigate(Screen.Import.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        onConnectMinecraft = { packForgeViewModel.saveMinecraftFolderUri(it) },
                        onDisconnectMinecraft = { packForgeViewModel.disconnectMinecraft() }
                    )
                }
                composable(Screen.Studio.route) {
                    val savedModpacks by packForgeViewModel.savedModpacks.collectAsStateWithLifecycle()
                    val isImporting by packForgeViewModel.isImporting.collectAsStateWithLifecycle()
                    val importProgress by packForgeViewModel.importProgress.collectAsStateWithLifecycle()
                    val webImportError by packForgeViewModel.webImportError.collectAsStateWithLifecycle()
                    StudioScreen(
                        viewModel = packForgeViewModel,
                        themeViewModel = themeViewModel,
                        savedModpacks = savedModpacks,
                        isImporting = isImporting,
                        importProgress = importProgress,
                        webImportError = webImportError,
                        onDeleteModpack = { packForgeViewModel.deleteFromHistory(appContext, it) },
                        onLoadModpack = { summary ->
                            // El ViewModel avisa cuando los addons ya están
                            // hidratados. Antes se navegaba de inmediato y el
                            // ImportScreen aparecía VACÍO hasta que terminaba
                            // la carga: eso se percibía como una lentitud de
                            // varios segundos al pulsar "Editar".
                            packForgeViewModel.loadModpack(summary) {
                                navController.navigate(Screen.Import.route) {
                                    popUpTo(Screen.Studio.route)
                                }
                            }
                        },
                        onImportFromUrl = { url, pageUrl, siteName ->
                            packForgeViewModel.importFromWebUrl(appContext, url, pageUrl, siteName)
                        },
                        onClearError = { packForgeViewModel.clearWebError() }
                    )
                }
            }

            // Overlay global: bloquea la UI con diálogos de fusión durante la
            // re-fusión desde "My Modpacks" (la exportación normal tiene su propia UI).
            MergeOverlay()

            // ── Diálogo al compartir un modpack de PackForge desde apps de terceros ──
            pendingShareModpacks?.let { modpacks ->
                ExpressiveAlertDialog(
                    onDismissRequest = {
                        pendingShareModpacks = null
                        pendingShareAddons = emptyList()
                    },
                    title = {
                        Text(
                            "Modpack de PackForge detectado",
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Este archivo es un modpack creado con PackForge.",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "¿Dónde quieres guardarlo?",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    confirmButton = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Button(
                                onClick = {
                                    val hadAddons = pendingShareAddons.isNotEmpty()
                                    packForgeViewModel.importSharedModpacksToLibrary(modpacks, pendingShareAddons)
                                    pendingShareModpacks = null
                                    pendingShareAddons = emptyList()
                                    val target = if (hadAddons) {
                                        com.packforge.app.ui.navigation.Screen.Import.route
                                    } else {
                                        com.packforge.app.ui.navigation.Screen.Studio.route
                                    }
                                    if (navController.currentDestination?.route != target) {
                                        navController.navigate(target) {
                                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.share_save_library), fontWeight = FontWeight.SemiBold)
                            }
                            OutlinedButton(
                                onClick = {
                                    packForgeViewModel.importAddons(appContext, modpacks + pendingShareAddons)
                                    pendingShareModpacks = null
                                    pendingShareAddons = emptyList()
                                    if (navController.currentDestination?.route != com.packforge.app.ui.navigation.Screen.Import.route) {
                                        navController.navigate(com.packforge.app.ui.navigation.Screen.Import.route) {
                                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.share_import_addon), fontWeight = FontWeight.Medium)
                            }
                            TextButton(
                                onClick = {
                                    pendingShareModpacks = null
                                    pendingShareAddons = emptyList()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.common_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    dismissButton = {}
                )
            }
        }
    }
}

/**
 * Título de la pestaña Conflictos (logo que cambia entre gris/verde/rojo).
 *
 * Colecta SUS flows internamente en lugar de recibirlos de la raíz de
 * [PackForgeApp]: así solo se recompone cuando cambian los conflictos, y no
 * con cada tick de progreso de importación/exportación. El cálculo de
 * "cuántos sin resolver" vive aquí, también, para no arrastrar listas a la
 * carcasa en cada fotograma.
 */
@Composable
private fun ConflictTitle(packForgeViewModel: PackForgeViewModel) {
    val addons by packForgeViewModel.addons.collectAsStateWithLifecycle()
    val conflicts by packForgeViewModel.conflicts.collectAsStateWithLifecycle()
    val resolutions by packForgeViewModel.resolutions.collectAsStateWithLifecycle()
    val mergeConflicts by packForgeViewModel.mergeConflicts.collectAsStateWithLifecycle()

    val unresolvedCount = conflicts.count {
        it.resolution == com.packforge.app.domain.model.ConflictResolution.UNRESOLVED
    } + mergeConflicts.count { it.resolution.isNullOrBlank() }
    val conflictState = when {
        addons.isEmpty() && conflicts.isEmpty() && mergeConflicts.isEmpty() -> 0 // Grey
        unresolvedCount > 0 -> 2 // Red
        else -> 1 // Green
    }

    Crossfade(
        targetState = conflictState,
        animationSpec = tween(durationMillis = 350),
        label = "ConflictTitleCrossfade"
    ) { state ->
        val drawableRes = when (state) {
            0 -> R.drawable.title_conflictos_grey
            1 -> R.drawable.title_conflictos_green
            else -> R.drawable.title_conflictos_red
        }
        Image(
            painter = painterResource(id = drawableRes),
            contentDescription = "CONFLICTOS",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .height(32.dp)
                .wrapContentWidth(Alignment.Start)
        )
    }
}

