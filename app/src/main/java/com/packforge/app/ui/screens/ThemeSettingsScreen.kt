package com.packforge.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.packforge.app.data.AccentColor
import com.packforge.app.ui.components.PackForgeTopBar
import com.packforge.app.ui.viewmodel.ThemeViewModel
import kotlinx.coroutines.launch

@Composable
fun ThemeSettingsScreen(
    viewModel: ThemeViewModel,
    onBack: () -> Unit
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            PackForgeTopBar(
                title = "Ajustes de Tema",
                onBackClick = onBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Modo Oscuro
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Modo oscuro", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = prefs.darkMode,
                    onCheckedChange = { scope.launch { viewModel.setDarkMode(it) } }
                )
            }

            // Animaciones Expresivas
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Animaciones expresivas", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = prefs.expressiveMotion,
                    onCheckedChange = { scope.launch { viewModel.setExpressiveMotion(it) } }
                )
            }

            // Selector de Color
            Text("Color de acento", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AccentColor.values().forEach { color ->
                    val isSelected = prefs.accentColor == color
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(color.hex))
                            .clickable { scope.launch { viewModel.setAccentColor(color) } },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(Icons.Default.Check, null, tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}
