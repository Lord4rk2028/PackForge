package com.packforge.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Slider
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.packforge.app.ui.components.PackForgeTopBar
import com.packforge.app.ui.components.bounceClick
import com.packforge.app.ui.viewmodel.ThemeViewModel

@Composable
fun ThemeSettingsScreen(
    viewModel: ThemeViewModel,
    onBack: () -> Unit
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ⭐ VISTA PREVIA EN VIVO
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(22.dp)),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Vista Previa del Tema",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Icon(
                                imageVector = Icons.Default.Palette,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(6.dp).size(16.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {},
                            modifier = Modifier.bounceClick(scaleDown = 0.94f) {}
                        ) {
                            Text("Primario")
                        }

                        FilledTonalButton(
                            onClick = {},
                            modifier = Modifier.bounceClick(scaleDown = 0.94f) {}
                        ) {
                            Text("Tonal")
                        }

                        Surface(
                            shape = RoundedCornerShape(100.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "Activo",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // ⭐ SELECTOR DE COLOR INTERACTIVO & ARCOÍRIS
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Selector de Color Personalizado",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    InteractiveColorPicker(
                        selectedHex = prefs.accentHex,
                        onColorChanged = { newHex ->
                            viewModel.setAccentHex(newHex)
                            hexInput = newHex
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    // ⭐ CÓDIGO HEX COMPACTO CON COPIAR / PEGAR
                    val clipboardManager = LocalClipboardManager.current
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = try { Color(android.graphics.Color.parseColor(prefs.accentHex)) } catch (_: Exception) { MaterialTheme.colorScheme.primary },
                            modifier = Modifier
                                .size(44.dp)
                                .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        ) {}

                        OutlinedTextField(
                            value = hexInput,
                            onValueChange = { if (it.length <= 7) updateHex(it) },
                            label = { Text("HEX") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                        )

                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(prefs.accentHex))
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copiar HEX",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                val clip = clipboardManager.getText()?.text
                                if (!clip.isNullOrBlank()) updateHex(clip.trim())
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = "Pegar HEX",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // ⭐ PALETAS Y PRESETS MINECRAFT
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Paletas Temáticas Bedrock",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                val presets = listOf(
                    "#2ECC71" to "Esmeralda",
                    "#00E5FF" to "Diamante",
                    "#FF1744" to "Redstone",
                    "#AA00FF" to "Amatista",
                    "#FFD600" to "Oro",
                    "#FF6B35" to "Blaze",
                    "#00E676" to "Slime",
                    "#2979FF" to "Lapis",
                    "#D87040" to "Cobre",
                    "#5C5753" to "Netherite",
                    "#00B0FF" to "Ender",
                    "#FF3D71" to "Rubí"
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(presets.take(6), presets.drop(6)).forEach { rowPresets ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            rowPresets.forEach { (hex, name) ->
                                val isSelected = prefs.accentHex.equals(hex, ignoreCase = true)
                                val color = Color(android.graphics.Color.parseColor(hex))

                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(
                                            BorderStroke(
                                                if (isSelected) 3.dp else 1.dp,
                                                if (isSelected) MaterialTheme.colorScheme.onSurface else Color.White.copy(alpha = 0.3f)
                                            ),
                                            CircleShape
                                        )
                                        .bounceClick(scaleDown = 0.88f) {
                                            viewModel.setAccentHex(hex)
                                            hexInput = hex
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = name,
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ⭐ GRUPO DE AJUSTES (SWITCHES)
            Text(
                text = "Comportamiento y Contraste",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ThemeSwitchItem(
                        label = "Modo oscuro",
                        description = "Tema con fondo oscuro estándar",
                        checked = prefs.darkMode,
                        onCheckedChange = { viewModel.setDarkMode(it) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    ThemeSwitchItem(
                        label = "Negro AMOLED Puro",
                        description = "Negro absoluto para pantallas OLED y ahorro de batería",
                        checked = prefs.amoledMode,
                        enabled = prefs.darkMode,
                        onCheckedChange = { viewModel.setAmoledMode(it) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    ThemeSwitchItem(
                        label = "Colores vivos",
                        description = "Aumenta la saturación del color de acento",
                        checked = prefs.vividColors,
                        onCheckedChange = { viewModel.setVividColors(it) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    ThemeSwitchItem(
                        label = "Animaciones expresivas",
                        description = "Física de rebote suave en transiciones y botones",
                        checked = prefs.expressiveMotion,
                        onCheckedChange = { viewModel.setExpressiveMotion(it) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    ThemeSwitchItem(
                        label = "Registro detallado de archivos (Debug)",
                        description = "Muestra trazas avanzadas durante la compresión",
                        checked = prefs.verboseFileLogs,
                        onCheckedChange = { viewModel.setVerboseFileLogs(it) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@Composable
private fun ThemeSwitchItem(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.8f else 0.4f)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
fun InteractiveColorPicker(
    selectedHex: String,
    onColorChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val initialHsv = remember(selectedHex) {
        val color = try {
            android.graphics.Color.parseColor(if (selectedHex.startsWith("#")) selectedHex else "#$selectedHex")
        } catch (_: Exception) {
            android.graphics.Color.parseColor("#2ECC71")
        }
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        hsv
    }

    var hue by remember(selectedHex) { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember(selectedHex) { mutableFloatStateOf(initialHsv[1]) }
    var value by remember(selectedHex) { mutableFloatStateOf(initialHsv[2]) }

    fun emitColor(h: Float, s: Float, v: Float) {
        val argb = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
        val hex = String.format("#%06X", 0xFFFFFF and argb)
        onColorChanged(hex)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── 1. Cuadro de Saturación y Brillo (Lienzo 2D) ──
        // ⚠️ IMPORTANTE: Usar pointerInput CON nestedScroll para evitar conflicto con scroll externo
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    RoundedCornerShape(16.dp)
                )
                .pointerInput(hue) {
                    detectTapGestures { offset ->
                        sat = (offset.x / size.width).coerceIn(0f, 1f)
                        value = (1f - offset.y / size.height).coerceIn(0f, 1f)
                        emitColor(hue, sat, value)
                    }
                }
                .pointerInput(hue) {
                    detectDragGestures(onDrag = { change, _ ->
                        change.consume()
                        sat = (change.position.x / size.width).coerceIn(0f, 1f)
                        value = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                        emitColor(hue, sat, value)
                    })
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val pureHueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                drawRect(brush = Brush.horizontalGradient(listOf(Color.White, pureHueColor)))
                drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))

                val selX = sat * size.width
                val selY = (1f - value) * size.height
                drawCircle(
                    color = Color.Black.copy(alpha = 0.5f),
                    radius = 11.dp.toPx(),
                    center = Offset(selX, selY),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                )
                drawCircle(
                    color = Color.White,
                    radius = 9.dp.toPx(),
                    center = Offset(selX, selY),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
                )
            }
        }

        // ── 2. Barra Arcoíris de Tono ──
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Tono Arcoíris",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val rainbowColors = remember {
                listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(100.dp))
                    .background(Brush.horizontalGradient(rainbowColors))
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            hue = ((offset.x / size.width) * 360f).coerceIn(0f, 360f)
                            emitColor(hue, sat, value)
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(onDrag = { change, _ ->
                            change.consume()
                            hue = ((change.position.x / size.width) * 360f).coerceIn(0f, 360f)
                            emitColor(hue, sat, value)
                        })
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val thumbX = (hue / 360f) * size.width
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.4f),
                        radius = 12.dp.toPx(),
                        center = Offset(thumbX, size.height / 2),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 10.dp.toPx(),
                        center = Offset(thumbX, size.height / 2),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
                    )
                }
            }
        }
    }
}
