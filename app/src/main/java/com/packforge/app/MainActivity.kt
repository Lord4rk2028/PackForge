package com.packforge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp


import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import com.packforge.app.domain.model.OperationProgress
import com.packforge.app.ui.components.PackForgeTopBar
import com.packforge.app.ui.navigation.Screen
import com.packforge.app.ui.navigation.getScreenFromRoute
import com.packforge.app.ui.screens.ConflictsScreen
import com.packforge.app.ui.screens.ExportSetupScreen
import com.packforge.app.ui.screens.ImportScreen
import com.packforge.app.ui.screens.MergeOverlay
import com.packforge.app.ui.screens.StudioScreen
import com.packforge.app.ui.theme.PackForgeTheme
import com.packforge.app.ui.viewmodel.PackForgeEvent
import com.packforge.app.ui.viewmodel.PackForgeViewModel
import com.packforge.app.ui.viewmodel.ThemeViewModel
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

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
        setContent {
            val themeViewModel: ThemeViewModel = viewModel()
            val prefs by themeViewModel.preferences.collectAsStateWithLifecycle()
            PackForgeTheme(prefs = prefs) {
                PackForgeApp(themeViewModel = themeViewModel)
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
    val snackbarHostState = remember { SnackbarHostState() }

    val addons by packForgeViewModel.addons.collectAsStateWithLifecycle()
    val conflicts by packForgeViewModel.conflicts.collectAsStateWithLifecycle()
    val resolutions by packForgeViewModel.resolutions.collectAsStateWithLifecycle()
    val metadata by packForgeViewModel.metadata.collectAsStateWithLifecycle()
    val isImporting by packForgeViewModel.isImporting.collectAsStateWithLifecycle()
    val exportState by packForgeViewModel.exportState.collectAsStateWithLifecycle()
    val isMinecraftInstalled by packForgeViewModel.isMinecraftInstalled.collectAsStateWithLifecycle()
    val minecraftVersion by packForgeViewModel.minecraftVersion.collectAsStateWithLifecycle()
    val compatibilityScore by packForgeViewModel.compatibilityScore.collectAsStateWithLifecycle()
    val criticalCount by packForgeViewModel.criticalConflictsCount.collectAsStateWithLifecycle()
    val importProgress by packForgeViewModel.importProgress.collectAsStateWithLifecycle()
    val webImportError by packForgeViewModel.webImportError.collectAsStateWithLifecycle()
    val minecraftUri by packForgeViewModel.minecraftUri.collectAsStateWithLifecycle()
    val conflictStrategy by packForgeViewModel.conflictStrategy.collectAsStateWithLifecycle()
    val mergeConflicts by packForgeViewModel.mergeConflicts.collectAsStateWithLifecycle()
    val activeWebSource by packForgeViewModel.activeWebSource.collectAsStateWithLifecycle()
    val showMyModpacks by packForgeViewModel.showMyModpacks.collectAsStateWithLifecycle()
    val showThemeSettings by packForgeViewModel.showThemeSettings.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        packForgeViewModel.events.collectLatest { event ->
            when (event) {
                is PackForgeEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                PackForgeEvent.Vibration -> haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentScreen = getScreenFromRoute(currentRoute)
    

    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
        topBar = {
            if (activeWebSource == null && !showMyModpacks && !showThemeSettings) {
                PackForgeTopBar(
                    title = currentScreen.title,
                    actions = {
                        if (currentScreen == Screen.Import && addons.isNotEmpty()) {
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                packForgeViewModel.clearAll()
                            }) {
                                Icon(Icons.Default.Delete, "Limpiar todo", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (activeWebSource == null) {
                Surface(tonalElevation = 3.dp, shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
                    NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
                        val screens = listOf(Screen.Import, Screen.Conflicts, Screen.Export, Screen.Studio)
                        screens.forEach { screen ->
                            val selected = currentRoute == screen.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (currentRoute != screen.route) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        navController.navigate(screen.route) {
                                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = {
                                    if (screen == Screen.Conflicts) {
                                        BadgedBox(badge = {
                                            if (criticalCount > 0) {
                                                Badge(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) {
                                                    Text(text = if (criticalCount > 9) "9+" else criticalCount.toString())
                                                }
                                            }
                                        }) {
                                            Icon(if (selected) screen.iconFilled else screen.iconOutlined, screen.title)
                                        }
                                    } else {
                                        Icon(if (selected) screen.iconFilled else screen.iconOutlined, screen.title)
                                    }
                                },
                                label = {
                                    Text(text = screen.title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                }
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            NavHost(navController = navController, startDestination = Screen.Import.route, modifier = Modifier.fillMaxSize()) {
                composable(Screen.Import.route) {
                    ImportScreen(
                        addons = addons, conflicts = conflicts, isImporting = isImporting,
                        importProgress = importProgress, compatibilityScore = compatibilityScore,
                        onImportUris = { packForgeViewModel.importAddons(appContext, it) },
                        onRemoveAddon = { packForgeViewModel.removeAddon(it) },
                        onToggleAddon = { packForgeViewModel.toggleAddon(it) },
                        onMoveAddon = { id, dir -> packForgeViewModel.moveAddon(id, dir) }
                    )
                }
                composable(Screen.Conflicts.route) {
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
                    ExportSetupScreen(
                        viewModel = packForgeViewModel,
                        metadata = metadata, addons = addons, conflicts = conflicts,
                        resolutions = resolutions, exportState = exportState,
                        isMinecraftInstalled = isMinecraftInstalled, minecraftVersion = minecraftVersion,
                        minecraftUri = minecraftUri,
                        onMetadataChange = { packForgeViewModel.updateMetadata(it) },
                        onExport = { uri, toMc -> packForgeViewModel.exportModpack(appContext, uri, toMc) },
                        onResetExport = { packForgeViewModel.resetExportState() },
                        onConnectMinecraft = { packForgeViewModel.saveMinecraftFolderUri(it) },
                        onDisconnectMinecraft = { packForgeViewModel.disconnectMinecraft() }
                    )
                }
                composable(Screen.Studio.route) {
                    val savedModpacks by packForgeViewModel.savedModpacks.collectAsStateWithLifecycle()
                    StudioScreen(
                        viewModel = packForgeViewModel,
                        themeViewModel = themeViewModel,
                        savedModpacks = savedModpacks,
                        isImporting = isImporting,
                        importProgress = importProgress,
                        webImportError = webImportError,
                        onDeleteModpack = { packForgeViewModel.deleteFromHistory(appContext, it) },
                        onLoadModpack = {
                            packForgeViewModel.loadModpack(it)
                            navController.navigate(Screen.Import.route) { popUpTo(Screen.Studio.route) }
                        },
                        onImportFromUrl = { packForgeViewModel.importFromWebUrl(appContext, it) },
                        onClearError = { packForgeViewModel.clearWebError() }
                    )
                }
            }

            // Overlay global: bloquea la UI con diálogos de fusión durante la
            // re-fusión desde "My Modpacks" (la exportación normal tiene su propia UI).
            MergeOverlay()
        }
    }
}

