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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.packforge.app.ui.components.PackForgeTopBar
import com.packforge.app.ui.navigation.Screen
import com.packforge.app.ui.navigation.getScreenFromRoute
import com.packforge.app.ui.screens.ConflictsScreen
import com.packforge.app.ui.screens.ExportSetupScreen
import com.packforge.app.ui.screens.ImportScreen
import com.packforge.app.ui.screens.StudioScreen
import com.packforge.app.ui.theme.PackForgeTheme
import com.packforge.app.ui.viewmodel.PackForgeEvent
import com.packforge.app.ui.viewmodel.PackForgeViewModel
import com.packforge.app.ui.viewmodel.ThemeViewModel
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
    viewModel: PackForgeViewModel = viewModel(),
    themeViewModel: ThemeViewModel = viewModel()
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    // Estados
    val addons by viewModel.addons.collectAsStateWithLifecycle()
    val conflicts by viewModel.conflicts.collectAsStateWithLifecycle()
    val resolutions by viewModel.resolutions.collectAsStateWithLifecycle()
    val metadata by viewModel.metadata.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val isMinecraftInstalled by viewModel.isMinecraftInstalled.collectAsStateWithLifecycle()
    val minecraftVersion by viewModel.minecraftVersion.collectAsStateWithLifecycle()
    val compatibilityScore by viewModel.compatibilityScore.collectAsStateWithLifecycle()
    val criticalCount by viewModel.criticalConflictsCount.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val webImportError by viewModel.webImportError.collectAsStateWithLifecycle()
    val minecraftUri by viewModel.minecraftUri.collectAsStateWithLifecycle()
    val conflictStrategy by viewModel.conflictStrategy.collectAsStateWithLifecycle()
    val mergeConflicts by viewModel.mergeConflicts.collectAsStateWithLifecycle()
    val activeWebSource by viewModel.activeWebSource.collectAsStateWithLifecycle()
    val showMyModpacks by viewModel.showMyModpacks.collectAsStateWithLifecycle()
    val showThemeSettings by viewModel.showThemeSettings.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is PackForgeEvent.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                PackForgeEvent.Vibration -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentScreen = getScreenFromRoute(currentRoute)

    Scaffold(
        topBar = {
            // Cuando el navegador interno de Studio, la sub-pantalla "My Modpacks"
            // o los "Ajustes de Tema" están abiertos, ocultamos la barra global
            // para evitar la doble barra fea y alta (esas pantallas ya tienen su
            // propia barra).
            if (activeWebSource == null && !showMyModpacks && !showThemeSettings) {
                PackForgeTopBar(
                    title = currentScreen.title,
                    actions = {
                        if (currentScreen == Screen.Import && addons.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.clearAll()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Limpiar todo",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                NavigationBar(
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp
                ) {
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
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.error,
                                                contentColor = MaterialTheme.colorScheme.onError
                                            ) {
                                                Text(text = if (criticalCount > 9) "9+" else criticalCount.toString())
                                            }
                                        }
                                    }) {
                                        Icon(
                                            imageVector = if (selected) screen.iconFilled else screen.iconOutlined,
                                            contentDescription = screen.title
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = if (selected) screen.iconFilled else screen.iconOutlined,
                                        contentDescription = screen.title
                                    )
                                }
                            },
                            label = {
                                Text(
                                    text = screen.title,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        )
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
                        onImportUris = { viewModel.importAddons(context, it) },
                        onRemoveAddon = { viewModel.removeAddon(it) },
                        onToggleAddon = { viewModel.toggleAddon(it) },
                        onMoveAddon = { id, dir -> viewModel.moveAddon(id, dir) }
                    )
                }
                composable(Screen.Conflicts.route) {
                    ConflictsScreen(
                        conflicts = conflicts, addons = addons, resolutions = resolutions,
                        onResolve = { cId, wId -> viewModel.resolveConflict(cId, wId) },
                        onDismiss = { viewModel.dismissConflict(it) },
                        conflictStrategy = conflictStrategy,
                        onConflictStrategyChange = { viewModel.setConflictStrategy(it) },
                        mergeConflicts = mergeConflicts,
                        onResolveMergeConflict = { id, resolution -> viewModel.resolveMergeConflict(id, resolution) }
                    )
                }
                composable(Screen.Export.route) {
                    ExportSetupScreen(
                        viewModel = viewModel,
                        metadata = metadata, addons = addons, conflicts = conflicts,
                        resolutions = resolutions, exportState = exportState,
                        isMinecraftInstalled = isMinecraftInstalled, minecraftVersion = minecraftVersion,
                        minecraftUri = minecraftUri,
                        onMetadataChange = { viewModel.updateMetadata(it) },
                        onExport = { uri, toMc -> viewModel.exportModpack(context, uri, toMc) },
                        onResetExport = { viewModel.resetExportState() },
                        onConnectMinecraft = { viewModel.saveMinecraftFolderUri(it) },
                        onDisconnectMinecraft = { viewModel.disconnectMinecraft() }
                    )
                }
                composable(Screen.Studio.route) {
                    val savedModpacks by viewModel.savedModpacks.collectAsStateWithLifecycle()
                    StudioScreen(
                        viewModel = viewModel,
                        themeViewModel = themeViewModel,
                        savedModpacks = savedModpacks,
                        isImporting = isImporting,
                        importProgress = importProgress,
                        webImportError = webImportError,
                        onDeleteModpack = { viewModel.deleteFromHistory(context, it) },
                        onLoadModpack = {
                            viewModel.loadModpack(it)
                            navController.navigate(Screen.Import.route) { popUpTo(Screen.Studio.route) { inclusive = false } }
                        },
                        onImportFromUrl = { viewModel.importFromWebUrl(context, it) },
                        onClearError = { viewModel.clearWebError() }
                    )
                }
            }
        }
    }
}