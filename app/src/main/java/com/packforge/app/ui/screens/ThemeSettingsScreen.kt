package com.packforge.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    var hexInput by remember(prefs.accentHex) { mutableStateOf(prefs.accentHex) }

    fun updateHex(newHex: String) {
        val sanitized = if (newHex.startsWith("#")) newHex else "#$newHex"
        if (sanitized.matches(Regex("^#[0-9A-Fa-f]{6}$"))) {
            viewModel.setAccentHex(sanitized.uppercase())
        }
        hexInput = newHex
    }

    Scaffold(
        topBar = { PackForgeTopBar(title = "Ajustes de Tema", onBackClick = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ⭐ VISTA PREVIA
            Card(modifier = Modifier.fillMaxWidth().height(100.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {}) { Text("Botón") }
                    AssistChip(onClick = {}, label = { Text("Chip") })
                    Text("Texto", color = MaterialTheme.colorScheme.primary)
                }
            }

            // ⭐ SELECTOR HEX
            OutlinedTextField(
                value = hexInput,
                onValueChange = { if (it.length <= 7) updateHex(it) },
                label = { Text("Color Acento (HEX)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )

            // ⭐ PRESETS
            val presets = listOf("#2ECC71", "#FF6B35", "#00E5FF", "#FF1744", "#FFD600", "#AA00FF", "#00E676", "#FF3D71")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                presets.forEach { hex ->
                    Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(hex))).clickable { viewModel.setAccentHex(hex) })
                }
            }

            // ⭐ SWITCHES
            ThemeSwitch("Modo oscuro", prefs.darkMode) { viewModel.setDarkMode(it) }
            ThemeSwitch("Negro AMOLED", prefs.amoledMode, enabled = prefs.darkMode) { viewModel.setAmoledMode(it) }
            ThemeSwitch("Colores vivos", prefs.vividColors) { viewModel.setVividColors(it) }
            ThemeSwitch("Animaciones expresivas", prefs.expressiveMotion) { viewModel.setExpressiveMotion(it) }
            ThemeSwitch("Registro detallado de archivos (debug)", prefs.verboseFileLogs) { viewModel.setVerboseFileLogs(it) }
        }
    }
}

@Composable
fun ThemeSwitch(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
