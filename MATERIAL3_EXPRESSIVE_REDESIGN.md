# PackForge - Rediseño Material 3 Expressive

## 📋 Índice
1. [¿Qué es Material 3 Expressive?](#qué-es-material-3-expressive)
2. [Análisis de la UI Actual](#análisis-de-la-ui-actual)
3. [Principios de Diseño Expressive](#principios-de-diseño-expressive)
4. [Propuesta de Rediseño por Pantalla](#propuesta-de-rediseño-por-pantalla)
5. [Sistema de Diseño Personalizado](#sistema-de-diseño-personalizado)
6. [Implementación Técnica](#implementación-técnica)
7. [Mejoras de Responsividad y Fluidez](#mejoras-de-responsividad-y-fluidez)

---

## 🎨 ¿Qué es Material 3 Expressive?

**Material 3 Expressive** es una evolución del sistema de diseño baseline de Material 3 que enfatiza:

### Características Principales

#### 1. **Tipografía Expresiva**
- **3 escalas de texto**: Small (compacta), Medium (estándar), Large (generosa)
- **Letter-spacing aumentado**: tracking +0.5sp a +2sp para jerarquía visual
- **Pesos dinámicos**: uso más audaz de ExtraBold (800) y Light (300)
- **Altura de línea generosa**: 140-160% vs 120-130% baseline

```kotlin
// Ejemplo de tipografía Expressive
val ExpressiveTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = 0.5.sp,
        fontWeight = FontWeight.ExtraBold
    ),
    headlineLarge = TextStyle(
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.Bold
    ),
    titleLarge = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.5.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
        fontWeight = FontWeight.Normal
    )
)
```

#### 2. **Formas Expresivas (Shapes)**
- **Esquinas redondeadas prominentes**: 0dp, 12dp, 20dp, 28dp (vs baseline 8dp, 12dp, 16dp)
- **Asimetría intencional**: top-start radius diferente a bottom-end
- **Combinación de formas**: rectángulos con una esquina muy redondeada (28dp) y otras sutiles (8dp)

```kotlin
val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),  // vs 16dp baseline
    large = RoundedCornerShape(28.dp),   // vs 24dp baseline
    extraLarge = RoundedCornerShape(32.dp)
)

// Forma asimétrica expresiva
val HeroCardShape = RoundedCornerShape(
    topStart = 28.dp,
    topEnd = 28.dp,
    bottomStart = 12.dp,
    bottomEnd = 12.dp
)
```

#### 3. **Color Expresivo**
- **Saturación aumentada**: tonos más vivos (40-60% vs 30-50% baseline)
- **Contraste audaz**: uso de accent colors en 80-90% de saturación
- **Gradientes sutiles**: overlays con alpha 0.05-0.15
- **Estados con más presencia**: hover/pressed con alpha 0.12-0.20 (vs 0.08-0.12)

```kotlin
// Paleta Expressive con saturación aumentada
val ExpressiveLightScheme = lightColorScheme(
    primary = Color(0xFF6750A4),        // Púrpura saturado
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    tertiary = Color(0xFF7D5260),       // Tonos más vivos
    surface = Color(0xFFFFFBFE),
    surfaceVariant = Color(0xFFE7E0EC),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0)
)
```

#### 4. **Movimiento Expresivo**
- **Duraciones más largas**: 300-500ms vs 200-300ms baseline
- **Easing curves audaces**: `FastOutSlowIn` con overshoot sutil
- **Transformaciones 3D**: rotación, escala con `graphicsLayer`
- **Transiciones encadenadas**: revelar elementos en secuencia

```kotlin
// Animación expresiva con overshoot
val bounceIn = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,  // 0.6
    stiffness = Spring.StiffnessMedium
)

// Transición expresiva
AnimatedVisibility(
    visible = visible,
    enter = fadeIn(animationSpec = tween(400)) + 
            slideInVertically(
                animationSpec = bounceIn,
                initialOffsetY = { it / 2 }
            ) + scaleIn(initialScale = 0.8f),
    exit = fadeOut(animationSpec = tween(200)) + 
           slideOutVertically() + scaleOut()
)
```

#### 5. **Espaciado Expresivo**
- **Tokens más generosos**: 4, 8, 12, 16, 20, 24, 28, 32, 40, 48dp
- **Padding interno aumentado**: 20-24dp vs 16dp baseline en tarjetas
- **Gaps entre elementos**: 12-16dp vs 8dp baseline
- **Márgenes de pantalla**: 20-24dp vs 16dp

```kotlin
object ExpressiveSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 28.dp
    val huge = 32.dp
    val massive = 40.dp
}
```

#### 6. **Elevación y Sombras Expresivas**
- **Elevaciones más pronunciadas**: 6dp, 12dp, 24dp (vs 4dp, 8dp, 16dp)
- **Sombras coloreadas**: tint sutil con color primario (alpha 0.08)
- **Blur radius aumentado**: 24-32dp para elevaciones altas

---

## 🔍 Análisis de la UI Actual

### Pantallas Identificadas
1. **StudioScreen** (Centro de Modpacks) ⭐ Pantalla principal
2. **ImportScreen** (Selección de addons)
3. **ExportSetupScreen** (Configuración y exportación)
4. **ConflictsScreen** (Resolución de conflictos)
5. **MyModpacksScreen** (Biblioteca de modpacks)
6. **WebBrowserScreen** (Navegador interno MCPEDL/CurseForge)
7. **ThemeSettingsScreen** (Configuración de temas)
8. **McpedlSearchScreen** (Búsqueda específica)

### Elementos UI Actuales

#### StudioScreen (Hub Central)
**Estructura actual:**
- LazyColumn con cards (`StudioCard`)
- Título "Centro de Modpacks" + descripción
- Cards para: Mis Modpacks, Explorar Fuentes, Temas, Importar Archivo
- MorphingFab (botón flotante transformable)
- Diálogos: regeneración, actualizaciones

**Problemas detectados:**
- ❌ Cards con `ElevatedCard` básicas (elevation 3dp)
- ❌ RoundedCornerShape(20dp) uniforme — poco expresivo
- ❌ Tipografía mixta (headlineMedium + bodyMedium) sin jerarquía fuerte
- ❌ Espaciado 16dp estándar
- ❌ Transiciones simples sin overshoot
- ❌ No hay feedback háptico en interacciones

#### ModpackLibraryCard (Tarjeta Steam-like)
**Estructura actual:**
- Portada 16:9 con `CachedAsyncImage`
- Badge de contador de addons (circular)
- Badge de origen web (MCPEDL/etc) si aplica
- Metadata: nombre, versión, fecha
- Row de 4 botones: Editar, Compartir, Regenerar, Borrar

**Problemas:**
- ❌ Border 1dp con outlineVariant — poco expresivo
- ❌ Elevation 3dp fija
- ❌ `bounceClick` con scaleDown 0.98f — rebote mínimo
- ❌ Botones FilledTonalIconButton sin diferenciación visual fuerte
- ❌ No hay transición al cargar imagen (fade simple)

#### ImportScreen (Grid de Addons)
**Características:**
- Grid adaptativo (2-3 columnas según ancho)
- Cards de addon con checkbox, icono, nombre
- FloatingActionButton para continuar
- EmptyState con animación Lottie

**Problemas:**
- ❌ Grid estático sin transiciones entre items
- ❌ Cards básicas sin elevation dinámica
- ❌ Checkbox sin animación de check
- ❌ FAB sin morphing al activar
- ❌ No hay indicador de progreso al cargar iconos

---

## 🎯 Principios de Diseño Expressive para PackForge

### 1. **Jerarquía Visual Audaz**
- **Hero elements**: La portada del modpack en biblioteca debe ser un 20% más grande
- **Contraste de peso**: Títulos en ExtraBold, cuerpo en Normal, etiquetas en Light
- **Espaciado generoso**: 24dp entre secciones, 16dp entre elementos

### 2. **Formas con Personalidad**
- **Cards de modpack**: top redondeado 28dp, bottom 12dp (asimetría)
- **Botones primarios**: 28dp border radius (pill-like)
- **Chips/tags**: 20dp radius con padding 12dp horizontal
- **Diálogos**: 32dp radius en esquinas superiores

### 3. **Movimiento con Carácter**
- **Entrada de pantalla**: slide + scale con overshoot
- **Cards al hacer clic**: scale 0.92 con spring bounce
- **FAB morphing**: expansión con rotate + scale en 450ms
- **Diálogos**: fade + slide from bottom con backdrop blur

### 4. **Color con Propósito**
- **Accent colors**: Usar tertiary y error para badges importantes
- **Gradientes sutiles**: primary a primaryContainer en headers
- **Estados hover**: surfaceTint con alpha 0.15
- **Dark mode**: contraste 15:1 vs 12:1 baseline

### 5. **Feedback Táctil Inteligente**
```kotlin
// Niveles de feedback háptico
sealed class HapticLevel {
    object Light: HapticLevel()    // Hover, scroll snap
    object Medium: HapticLevel()   // Click, select
    object Heavy: HapticLevel()    // Success, delete confirm
}

fun performHaptic(view: View, level: HapticLevel) {
    when (level) {
        HapticLevel.Light -> view.performHapticFeedback(
            HapticFeedbackConstants.CLOCK_TICK
        )
        HapticLevel.Medium -> view.performHapticFeedback(
            HapticFeedbackConstants.CONTEXT_CLICK
        )
        HapticLevel.Heavy -> view.performHapticFeedback(
            HapticFeedbackConstants.LONG_PRESS
        )
    }
}
```

### 6. **Responsividad Adaptativa**
```kotlin
// Breakpoints Expressive
enum class WindowSize {
    Compact,    // < 600dp (teléfonos)
    Medium,     // 600-840dp (tablets pequeñas, plegables)
    Expanded    // > 840dp (tablets grandes)
}

@Composable
fun rememberWindowSize(): WindowSize {
    val configuration = LocalConfiguration.current
    return when {
        configuration.screenWidthDp < 600 -> WindowSize.Compact
        configuration.screenWidthDp < 840 -> WindowSize.Medium
        else -> WindowSize.Expanded
    }
}

// Uso adaptativo
val gridColumns = when (windowSize) {
    WindowSize.Compact -> 2
    WindowSize.Medium -> 3
    WindowSize.Expanded -> 4
}

val cardPadding = when (windowSize) {
    WindowSize.Compact -> 16.dp
    WindowSize.Medium -> 20.dp
    WindowSize.Expanded -> 24.dp
}
```

---

## 🖼️ Propuesta de Rediseño por Pantalla

### 1️⃣ StudioScreen Expressive

#### Cambios Visuales
```kotlin
@Composable
fun StudioScreenExpressive(
    viewModel: PackForgeViewModel,
    savedModpacks: List<SavedModpackSummary>,
    // ... params
) {
    val windowSize = rememberWindowSize()
    val listState = rememberLazyListState()
    val hapticFeedback = LocalHapticFeedback.current
    
    // Animación de entrada de pantalla
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(400)) + slideInVertically(
            spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            initialOffsetY = { it / 3 }
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    horizontal = when (windowSize) {
                        WindowSize.Compact -> 20.dp
                        else -> 24.dp
                    },
                    vertical = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp)  // vs 16dp
            ) {
                // Header con tipografía expresiva
                item {
                    Column(
                        modifier = Modifier.padding(bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Centro de Modpacks",
                            style = MaterialTheme.typography.displaySmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.5.sp
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Gestiona tus creaciones y fuentes de contenido",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Light,
                                letterSpacing = 0.3.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // Cards expresivas con animación staggered
                itemsIndexed(
                    listOf("Mis Modpacks", "Explorar", "Temas", "Importar")
                ) { index, title ->
                    ExpressiveStudioCard(
                        icon = getIconForCard(title),
                        title = title,
                        description = getDescriptionForCard(title),
                        badge = if (title == "Mis Modpacks") 
                            savedModpacks.size.toString() else null,
                        animationDelay = index * 80,  // Cascada
                        onClick = {
                            hapticFeedback.performHapticFeedback(
                                HapticFeedbackType.LongPress
                            )
                            handleCardClick(title)
                        }
                    )
                }
            }
            
            // FAB Expressive con morphing
            ExpressiveMorphingFab(
                expanded = listState.firstVisibleItemIndex == 0,
                items = getFabItems(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(28.dp)  // vs 16dp
            )
        }
    }
}
```

#### ExpressiveStudioCard
```kotlin
@Composable
fun ExpressiveStudioCard(
    icon: ImageVector,
    title: String,
    description: String,
    badge: String?,
    animationDelay: Int,
    onClick: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(animationDelay.toLong())
        visible = true
    }
    
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        )
    )
    
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300)) + 
                slideInHorizontally(tween(400, easing = FastOutSlowInEasing)) +
                scaleIn(initialScale = 0.8f, animationSpec = tween(400))
    ) {
        ElevatedCard(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            shape = RoundedCornerShape(
                topStart = 28.dp,
                topEnd = 28.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp
            ),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            elevation = CardDefaults.elevatedCardElevation(
                defaultElevation = 6.dp,  // vs 3dp
                pressedElevation = 12.dp,
                hoveredElevation = 8.dp
            )
        ) {
            Row(
                modifier = Modifier
                    .padding(24.dp),  // vs 16dp
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Icono con background circular expresivo
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(64.dp)  // vs 48dp
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .padding(16.dp)
                            .graphicsLayer {
                                // Rotación sutil en hover
                                rotationZ = if (pressed) -5f else 0f
                            }
                    )
                }
                
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.3.sp
                        )
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Light
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Badge expresivo
                badge?.let {
                    Surface(
                        shape = RoundedCornerShape(20.dp),  // vs 50%
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.animateContentSize()
                    ) {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.ExtraBold
                            ),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(
                                horizontal = 16.dp,
                                vertical = 8.dp
                            )
                        )
                    }
                }
            }
        }
    }
}
```

### 2️⃣ ModpackLibraryCard Expressive

```kotlin
@Composable
fun ExpressiveModpackCard(
    modpack: SavedModpackSummary,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onRegenerate: () -> Unit,
    modpackSource: ModpackSource?
) {
    val hapticFeedback = LocalHapticFeedback.current
    var hovered by remember { mutableStateOf(false) }
    val elevation by animateDpAsState(
        targetValue = if (hovered) 12.dp else 6.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
    )
    
    ElevatedCard(
        onClick = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            onLoad()
        },
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                // Parallax sutil en hover
                translationY = if (hovered) -4f else 0f
            },
        shape = RoundedCornerShape(
            topStart = 28.dp,
            topEnd = 28.dp,
            bottomStart = 20.dp,
            bottomEnd = 20.dp
        ),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = elevation
        ),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Portada 16:9 con overlay gradiente
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(
                        topStart = 28.dp,
                        topEnd = 28.dp
                    ))
            ) {
                CachedAsyncImage(
                    model = modpack.coverUriString,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    // Transición suave de carga
                    crossfade = 400
                )
                
                // Gradiente overlay expresivo
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.1f)
                                ),
                                startY = 0f,
                                endY = Float.POSITIVE_INFINITY
                            )
                        )
                )
                
                // Badges con formas expresivas
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Badge de addons
                    ExpressiveBadge(
                        icon = Icons.Filled.Extension,
                        text = "${modpack.addonCount}",
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                    
                    // Badge de fuente web
                    modpackSource?.let { source ->
                        ExpressiveBadge(
                            icon = Icons.Filled.Link,
                            text = source.site,
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            
            // Metadata con tipografía expresiva
            Column(
                modifier = Modifier.padding(20.dp),  // vs 12dp
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = modpack.name,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.3.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "v${modpack.version} · MC ${modpack.mcVersion}",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Light
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatModpackDate(modpack.createdAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            
            // Botones expresivos con iconos prominentes
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ExpressiveIconButton(
                    icon = Icons.Default.Edit,
                    contentDescription = "Editar",
                    onClick = {
                        hapticFeedback.performHapticFeedback(
                            HapticFeedbackType.LongPress
                        )
                        onLoad()
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                ExpressiveIconButton(
                    icon = Icons.Default.Share,
                    contentDescription = "Compartir",
                    onClick = onShare,
                    modifier = Modifier.weight(1f)
                )
                ExpressiveIconButton(
                    icon = Icons.Default.Refresh,
                    contentDescription = "Regenerar",
                    onClick = onRegenerate,
                    modifier = Modifier.weight(1f)
                )
                ExpressiveIconButton(
                    icon = Icons.Default.Delete,
                    contentDescription = "Eliminar",
                    onClick = onDelete,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun ExpressiveBadge(
    icon: ImageVector,
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        shape = RoundedCornerShape(20.dp),  // vs 50dp circular
        color = containerColor,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = contentColor
            )
        }
    }
}

@Composable
fun ExpressiveIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
    )
    
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier
            .height(56.dp)  // vs 38dp
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(20.dp),  // vs RoundedCornerShape default
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        contentPadding = PaddingValues(0.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp)  // vs 17dp
        )
    }
}
```

---

## 🛠️ Sistema de Diseño Personalizado

### Theme.kt Expressive

```kotlin
// ui/theme/Theme.kt
package com.packforge.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Colores Expressive
private val LightExpressiveScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceTint = Color(0xFF6750A4),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF313033),
    inverseOnSurface = Color(0xFFF4EFF4),
    inversePrimary = Color(0xFFD0BCFF),
    surfaceDim = Color(0xFFDED8E1),
    surfaceBright = Color(0xFFFFFBFE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainer = Color(0xFFF3EDF7),
    surfaceContainerHigh = Color(0xFFECE6F0),
    surfaceContainerHighest = Color(0xFFE6E0E9)
)

private val DarkExpressiveScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceTint = Color(0xFFD0BCFF),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE6E1E5),
    inverseOnSurface = Color(0xFF313033),
    inversePrimary = Color(0xFF6750A4),
    surfaceDim = Color(0xFF1C1B1F),
    surfaceBright = Color(0xFF3B383E),
    surfaceContainerLowest = Color(0xFF0F0D13),
    surfaceContainerLow = Color(0xFF1D1B20),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36343B)
)

// Tipografía Expressive
val ExpressiveTypography = Typography(
    // Display (headers principales)
    displayLarge = TextStyle(
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = 0.5.sp,
        fontWeight = FontWeight.ExtraBold
    ),
    displayMedium = TextStyle(
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.5.sp,
        fontWeight = FontWeight.ExtraBold
    ),
    displaySmall = TextStyle(
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.Bold
    ),
    // Headline (títulos sección)
    headlineLarge = TextStyle(
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.Bold
    ),
    headlineMedium = TextStyle(
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.SemiBold
    ),
    headlineSmall = TextStyle(
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.SemiBold
    ),
    // Title (subtítulos)
    titleLarge = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.5.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.3.sp,
        fontWeight = FontWeight.Medium
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.3.sp,
        fontWeight = FontWeight.Medium
    ),
    // Body (texto corrido)
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
        fontWeight = FontWeight.Normal
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
        fontWeight = FontWeight.Normal
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
        fontWeight = FontWeight.Normal
    ),
    // Label (etiquetas, botones)
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.3.sp,
        fontWeight = FontWeight.Bold
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
        fontWeight = FontWeight.SemiBold
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
        fontWeight = FontWeight.Medium
    )
)

// Formas Expressive
val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun PackForgeExpressiveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkExpressiveScheme
        else -> LightExpressiveScheme
    }
    
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ExpressiveTypography,
        shapes = ExpressiveShapes,
        content = content
    )
}
```

### Tokens de Espaciado

```kotlin
// ui/theme/Spacing.kt
package com.packforge.app.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object ExpressiveSpacing {
    val none: Dp = 0.dp
    val xxxs: Dp = 2.dp
    val xxs: Dp = 4.dp
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 20.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 28.dp
    val xxxl: Dp = 32.dp
    val huge: Dp = 40.dp
    val massive: Dp = 48.dp
    val enormous: Dp = 64.dp
}

// Uso
import com.packforge.app.ui.theme.ExpressiveSpacing

@Composable
fun MyCard() {
    Card(
        modifier = Modifier.padding(ExpressiveSpacing.lg)
    ) {
        Column(
            modifier = Modifier.padding(ExpressiveSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(ExpressiveSpacing.sm)
        ) {
            // Content
        }
    }
}
```

---

## ⚡ Mejoras de Responsividad y Fluidez

### 1. Sistema de Feedback Háptico

```kotlin
// ui/components/HapticFeedback.kt
package com.packforge.app.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

enum class ExpressiveHapticLevel {
    LIGHT,      // Hover, scroll snap, subtle feedback
    MEDIUM,     // Click, select, checkbox toggle
    HEAVY,      // Success action, delete confirm, major state change
    REJECT      // Error, invalid action
}

@Composable
fun rememberExpressiveHaptic(): ExpressiveHaptic {
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    return remember(view, haptic) {
        ExpressiveHaptic(view, haptic)
    }
}

class ExpressiveHaptic(
    private val view: android.view.View,
    private val haptic: HapticFeedback
) {
    fun perform(level: ExpressiveHapticLevel) {
        when (level) {
            ExpressiveHapticLevel.LIGHT -> {
                // Feedback muy sutil para hover/scroll
                view.performHapticFeedback(
                    HapticFeedbackConstants.CLOCK_TICK,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                )
            }
            ExpressiveHapticLevel.MEDIUM -> {
                // Click estándar en botones/cards
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            ExpressiveHapticLevel.HEAVY -> {
                // Acciones importantes (delete, regenerate, export)
                view.performHapticFeedback(
                    HapticFeedbackConstants.LONG_PRESS,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
            }
            ExpressiveHapticLevel.REJECT -> {
                // Feedback de error/rechazo
                view.performHapticFeedback(
                    HapticFeedbackConstants.REJECT,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                )
            }
        }
    }
}

// Uso en componentes
@Composable
fun ExpressiveButton(
    onClick: () -> Unit,
    text: String
) {
    val haptic = rememberExpressiveHaptic()
    
    Button(
        onClick = {
            haptic.perform(ExpressiveHapticLevel.MEDIUM)
            onClick()
        }
    ) {
        Text(text)
    }
}
```

### 2. Animaciones Expresivas Pre-construidas

```kotlin
// ui/components/ExpressiveAnimations.kt
package com.packforge.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

object ExpressiveAnimations {
    // Durations
    const val FAST = 200
    const val MEDIUM = 300
    const val SLOW = 400
    const val EXTRA_SLOW = 500
    
    // Springs
    val bouncy = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
    
    val smooth = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMedium
    )
    
    val snappy = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )
    
    // Easings
    val expressiveEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val overshootEasing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1.0f)
    
    // Transiciones predefinidas
    fun fadeSlideIn(
        duration: Int = MEDIUM,
        slideDistance: Int = 50
    ): EnterTransition {
        return fadeIn(animationSpec = tween(duration, easing = expressiveEasing)) +
                slideInVertically(
                    animationSpec = bouncy,
                    initialOffsetY = { slideDistance }
                ) +
                scaleIn(
                    initialScale = 0.92f,
                    animationSpec = tween(duration)
                )
    }
    
    fun fadeSlideOut(
        duration: Int = FAST
    ): ExitTransition {
        return fadeOut(animationSpec = tween(duration)) +
                slideOutVertically(animationSpec = tween(duration)) +
                scaleOut(targetScale = 0.92f)
    }
    
    fun scaleInBounce(): EnterTransition {
        return scaleIn(
            animationSpec = bouncy,
            initialScale = 0.7f
        ) + fadeIn(animationSpec = tween(MEDIUM))
    }
    
    fun scaleOutSnappy(): ExitTransition {
        return scaleOut(
            animationSpec = snappy,
            targetScale = 0.8f
        ) + fadeOut(animationSpec = tween(FAST))
    }
}

// Modifier extensions para animaciones expresivas
fun Modifier.expressiveClickScale(
    enabled: Boolean = true,
    scaleDown: Float = 0.92f
): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) scaleDown else 1f,
        animationSpec = ExpressiveAnimations.bouncy
    )
    
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                }
            )
        }
}

fun Modifier.expressiveHoverElevate(
    elevateBy: Dp = 4.dp
): Modifier = composed {
    var hovered by remember { mutableStateOf(false) }
    val translation by animateDpAsState(
        targetValue = if (hovered) -elevateBy else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        )
    )
    
    this
        .graphicsLayer {
            translationY = translation.toPx()
        }
        .pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { hovered = true },
                onDragEnd = { hovered = false },
                onDragCancel = { hovered = false }
            ) { _, _ -> }
        }
}
```

### 3. Grid Responsivo Avanzado

```kotlin
// ui/components/ResponsiveGrid.kt
package com.packforge.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class WindowSizeClass {
    Compact,    // < 600dp (teléfonos verticales)
    Medium,     // 600-840dp (tablets pequeñas, plegables, teléfonos horizontales)
    Expanded    // > 840dp (tablets grandes, desktops)
}

data class ResponsiveGridConfig(
    val columns: Int,
    val spacing: Dp,
    val padding: Dp,
    val itemAspectRatio: Float = 1f
)

@Composable
fun rememberWindowSizeClass(): WindowSizeClass {
    val configuration = LocalConfiguration.current
    return remember(configuration.screenWidthDp) {
        when {
            configuration.screenWidthDp < 600 -> WindowSizeClass.Compact
            configuration.screenWidthDp < 840 -> WindowSizeClass.Medium
            else -> WindowSizeClass.Expanded
        }
    }
}

@Composable
fun rememberResponsiveGridConfig(
    windowSize: WindowSizeClass = rememberWindowSizeClass(),
    compactColumns: Int = 2,
    mediumColumns: Int = 3,
    expandedColumns: Int = 4
): ResponsiveGridConfig {
    return remember(windowSize) {
        when (windowSize) {
            WindowSizeClass.Compact -> ResponsiveGridConfig(
                columns = compactColumns,
                spacing = 12.dp,
                padding = 16.dp,
                itemAspectRatio = 0.75f
            )
            WindowSizeClass.Medium -> ResponsiveGridConfig(
                columns = mediumColumns,
                spacing = 16.dp,
                padding = 20.dp,
                itemAspectRatio = 0.8f
            )
            WindowSizeClass.Expanded -> ResponsiveGridConfig(
                columns = expandedColumns,
                spacing = 20.dp,
                padding = 24.dp,
                itemAspectRatio = 0.85f
            )
        }
    }
}

@Composable
fun <T> ExpressiveResponsiveGrid(
    items: List<T>,
    modifier: Modifier = Modifier,
    config: ResponsiveGridConfig = rememberResponsiveGridConfig(),
    key: ((T) -> Any)? = null,
    contentType: (T) -> Any? = { null },
    itemContent: @Composable LazyGridItemScope.(T) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(config.columns),
        modifier = modifier,
        contentPadding = PaddingValues(config.padding),
        verticalArrangement = Arrangement.spacedBy(config.spacing),
        horizontalArrangement = Arrangement.spacedBy(config.spacing)
    ) {
        items(
            items = items,
            key = key,
            contentType = contentType,
            itemContent = itemContent
        )
    }
}
```

---

## 📱 Ejemplo Completo: ImportScreen Rediseñada

```kotlin
// ui/screens/ImportScreenExpressive.kt
@Composable
fun ImportScreenExpressive(
    viewModel: PackForgeViewModel,
    onNavigateToExport: () -> Unit,
    onBack: () -> Unit
) {
    val addons by viewModel.addons.collectAsStateWithLifecycle()
    val selectedAddons = remember { mutableStateListOf<String>() }
    val windowSize = rememberWindowSizeClass()
    val gridConfig = rememberResponsiveGridConfig(
        windowSize = windowSize,
        compactColumns = 2,
        mediumColumns = 3,
        expandedColumns = 4
    )
    val haptic = rememberExpressiveHaptic()
    
    Scaffold(
        topBar = {
            ExpressiveTopBar(
                title = "Seleccionar Addons",
                subtitle = "${addons.size} disponibles",
                onBack = onBack
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = selectedAddons.isNotEmpty(),
                enter = ExpressiveAnimations.scaleInBounce(),
                exit = ExpressiveAnimations.scaleOutSnappy()
            ) {
                ExpressiveExtendedFab(
                    text = "Continuar (${selectedAddons.size})",
                    icon = Icons.Default.ArrowForward,
                    onClick = {
                        haptic.perform(ExpressiveHapticLevel.HEAVY)
                        onNavigateToExport()
                    }
                )
            }
        }
    ) { padding ->
        if (addons.isEmpty()) {
            ExpressiveEmptyState(
                icon = Icons.Outlined.Extension,
                title = "No hay addons",
                description = "Importa addons desde archivos .mcaddon o descárgalos desde fuentes web",
                actionLabel = "Explorar fuentes",
                onAction = { /* navegar a explorar */ }
            )
        } else {
            ExpressiveResponsiveGrid(
                items = addons,
                modifier = Modifier.padding(padding),
                config = gridConfig,
                key = { it.id }
            ) { addon ->
                ExpressiveAddonCard(
                    addon = addon,
                    isSelected = selectedAddons.contains(addon.id),
                    onToggle = {
                        if (selectedAddons.contains(addon.id)) {
                            selectedAddons.remove(addon.id)
                            haptic.perform(ExpressiveHapticLevel.LIGHT)
                        } else {
                            selectedAddons.add(addon.id)
                            haptic.perform(ExpressiveHapticLevel.MEDIUM)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun ExpressiveAddonCard(
    addon: Addon,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) 
            MaterialTheme.colorScheme.primary
        else 
            MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(200)
    )
    
    val elevation by animateDpAsState(
        targetValue = if (isSelected) 8.dp else 2.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
    )
    
    ElevatedCard(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.75f)
            .expressiveClickScale(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = elevation
        ),
        border = BorderStroke(
            width = if (isSelected) 3.dp else 1.dp,
            color = borderColor
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Icono del addon
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .size(64.dp)
                        .align(Alignment.CenterHorizontally)
                ) {
                    CachedAsyncImage(
                        model = addon.iconPath,
                        contentDescription = addon.name,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                
                Spacer(Modifier.weight(1f))
                
                // Nombre
                Text(
                    text = addon.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                
                // Versión
                Text(
                    text = addon.version,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Checkbox expresivo
            AnimatedVisibility(
                visible = isSelected,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) +
                        fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 4.dp
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Seleccionado",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .padding(6.dp)
                            .size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ExpressiveEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icono con animación de entrada
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(100)
            visible = true
        }
        
        AnimatedVisibility(
            visible = visible,
            enter = scaleIn(
                spring(dampingRatio = Spring.DampingRatioLowBouncy),
                initialScale = 0.5f
            ) + fadeIn()
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(120.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp)
                )
            }
        }
        
        Spacer(Modifier.height(24.dp))
        
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            textAlign = TextAlign.Center
        )
        
        Spacer(Modifier.height(8.dp))
        
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.Light
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        
        Spacer(Modifier.height(32.dp))
        
        Button(
            onClick = onAction,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.height(56.dp)
        ) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
```

---

## ✅ Checklist de Implementación

### Fase 1: Fundamentos (Semana 1)
- [ ] Crear `Theme.kt` con colores/tipografía/formas Expressive
- [ ] Implementar `ExpressiveSpacing.kt` con tokens
- [ ] Crear `HapticFeedback.kt` con niveles
- [ ] Implementar `ExpressiveAnimations.kt` con transiciones
- [ ] Crear `ResponsiveGrid.kt` con breakpoints

### Fase 2: Componentes Base (Semana 2)
- [ ] `ExpressiveStudioCard` (reemplazo de StudioCard)
- [ ] `ExpressiveModpackCard` (reemplazo de ModpackLibraryCard)
- [ ] `ExpressiveAddonCard` (para ImportScreen)
- [ ] `ExpressiveBadge` (badges redondeados)
- [ ] `ExpressiveIconButton` (botones pill)
- [ ] `ExpressiveTopBar` (app bar con gradiente)
- [ ] `ExpressiveFab` (con morphing)

### Fase 3: Rediseño de Pantallas (Semanas 3-4)
- [ ] StudioScreen con cards expresivas
- [ ] ImportScreen con grid responsivo
- [ ] MyModpacksScreen con grid 2 columnas Steam-like
- [ ] ExportSetupScreen con formulario expresivo
- [ ] ConflictsScreen con cards de conflicto animadas
- [ ] WebBrowserScreen con toolbar expresivo
- [ ] ThemeSettingsScreen con radio buttons personalizados

### Fase 4: Refinamiento (Semana 5)
- [ ] Optimizar animaciones (60fps garantizado)
- [ ] Ajustar feedback háptico en todos los componentes
- [ ] Implementar lazy loading con placeholders shimmer
- [ ] Añadir transiciones de navegación expresivas
- [ ] Testing en diferentes tamaños de pantalla

### Fase 5: Polish (Semana 6)
- [ ] Dark mode optimizado con contraste 15:1
- [ ] Micro-interacciones (partículas, confetti)
- [ ] Sonidos sutiles opcionales
- [ ] Accessibility: TalkBack, high contrast
- [ ] Performance profiling con Jetpack Compose tools

---

## 🎯 Diferencias Clave: Baseline vs Expressive

| Aspecto | Baseline M3 | Expressive M3 | Cambio en PackForge |
|---------|-------------|---------------|---------------------|
| **Border Radius** | 8, 12, 16, 24dp | 12, 20, 28, 32dp | Cards 28dp top, 16dp bottom |
| **Elevación** | 2, 4, 8dp | 6, 12, 24dp | Tarjetas 6dp default, 12dp hover |
| **Letter Spacing** | 0-0.5sp | 0.3-1sp | Títulos +0.5sp, etiquetas +0.3sp |
| **Font Weight** | Regular/Medium/Bold | Light/SemiBold/ExtraBold | Headers ExtraBold, body Light |
| **Animation Duration** | 200-300ms | 300-500ms | Transiciones 400ms con overshoot |
| **Padding Cards** | 16dp | 20-24dp | ModpackCard 24dp interno |
| **Gap Spacing** | 8dp | 12-16dp | Grid 16dp spacing |
| **FAB Size** | 56dp | 64dp | FAB morphing más grande |
| **Button Height** | 40dp | 56dp | Botones más prominentes |
| **Icon Size Cards** | 24dp | 28-32dp | Iconos más expresivos |

---

## ♿ Reducir Movimiento y Niveles de Movimiento (IMPLEMENTADO)

> **Estado**: Fase 1 completada y compilada. Archivos:
> `ui/theme/MotionSystem.kt`, `ui/theme/ExpressiveAnimations.kt`,
> `ui/theme/PackForgeTheme.kt`, `ui/components/AnimationModifiers.kt`,
> `ui/viewmodel/ThemeViewModel.kt`, `data/ThemePreferences.kt`,
> `ui/screens/ThemeSettingsScreen.kt`.

### Los temas personalizados NO se ven afectados

El rediseño Expressive cambia **formas, movimiento, espaciado y elevación**.
**No toca el sistema de color**, así que la pantalla de Temas personalizados
queda intacta:

| Preferencia del usuario | Efecto del rediseño |
|-------------------------|---------------------|
| Color de acento (`accentHex`) | Sin cambios: mismo `ColorScheme` |
| Modo oscuro / AMOLED | Sin cambios |
| Colores vivos | Sin cambios |
| Sliders de tono, copiar/pegar, presets | Sin cambios |
| **Nuevo**: Reducir Movimiento | Se **añade** al final de la lista |

`PackForgeTheme` sigue llamándose con `ThemePreferences` y produce exactamente
el mismo `colorScheme`; lo único que se le añadió es publicar el nivel de
movimiento.

### Cómo se resuelve el nivel de movimiento

Se evalúa **en este orden**; el primero que se cumple decide:

| # | Condición | Nivel | Motivo |
|---|-----------|-------|--------|
| 1 | "Reducir Movimiento" activado | `NONE` | Preferencia explícita del usuario: manda sobre todo |
| 2 | Accesibilidad: `ANIMATOR_DURATION_SCALE = 0` (Android 9+) | `NONE` | Ajuste global obligatorio |
| 3 | Ahorro de batería activo | `MINIMAL` | La fluidez pasa a segundo plano |
| 4 | RAM total < 2 GB (gama baja) | `MINIMAL` | El rebote genera tirones visibles |
| 5 | Android 12+ (API 31+) | `FULL` | Rebote elástico completo |
| 6 | Android 10-11 (API 29-30) | `MODERATE` | Spring suave, sin overshoot |
| 7 | **Android 8-9 (API 26-28)** | `MINIMAL` | **minSdk del proyecto: degradación segura** |

En **Android 8.0 (API 26)** no existe el ajuste global de animaciones, por eso
el paso 2 está protegido con `Build.VERSION.SDK_INT >= P`: en API 26/27 se
asume movimiento normal salvo que el usuario use nuestro interruptor.

### Cómo se propaga sin tocar las pantallas

`PackForgeTheme` publica el nivel con `LocalMotionLevel`, y los componentes
`Modifier.composed` lo leen solos. Así, las ~50 pantallas y tarjetas que ya
usan `bounceClick` se adaptan **sin modificar una sola línea** de las pantallas:

```kotlin
val motionLevel = LocalMotionLevel.current   // dentro de un composed

val animationSpec = when (motionLevel) {
    MotionLevel.FULL -> spring(DampingRatioMediumBouncy, StiffnessLow) // sin cambios
    MotionLevel.MODERATE -> spring(DampingRatioNoBouncy, StiffnessMediumLow)
    MotionLevel.MINIMAL -> tween(100, FastOutSlowInEasing)
    MotionLevel.NONE -> tween(0)   // sin animación
}
```

**Garantía importante**: en `FULL` —un Android 12+ normal— el comportamiento es
idéntico al actual. El usuario no ve ningún cambio hasta que activa "Reducir
Movimiento" o usa un equipo antiguo.

### Optimización real aplicada en Fase 1

1. **`shimmerLoading` deja de animarse en `NONE`.** Era una animación
   *infinita* (el mayor coste continuo de CPU/batería de la app). Con "Reducir
   Movimiento" pasa a color plano, sin cambiar el layout.
2. **`bounceClick` con `NONE` no deforma el elemento**: escala 1.0, pero el clic
   y el háptico siguen funcionando igual.
3. **`MINIMAL` reduce la compresión a la mitad**, manteniendo el gesto reconocible.
4. **Transiciones parametrizadas** (`ExpressiveAnimations`): fade, scale y slide
   con duraciones 200/150/100/0 ms según nivel, evitando el overshoot en
   dispositivos antiguos.

### Migración de la preferencia antigua

La clave anterior era `expressive_motion` (**activada = animar**, por defecto
activada). La nueva es `reduce_motion` (**activada = no animar**, por defecto
desactivada). Para no alterar la elección de nadie, al leer se aplica:

| Estado guardado | Resultado nuevo |
|-----------------|-----------------|
| `reduce_motion` existe | Se usa ese valor |
| Solo `expressive_motion = false` | `reduceMotion = true` (sigue sin animaciones) |
| Solo `expressive_motion = true` | `reduceMotion = false` (animaciones normales) |
| Ninguna de las dos | `reduceMotion = false` |

Así, quien había desactivado las animaciones **no** las recupera sin querer tras
actualizar. La caché en SharedPreferences usa la misma regla, porque es la que
se lee durante el arranque en frío.

### Archivos de Fase 1

| Archivo | Rol |
|---------|-----|
| `ui/theme/MotionSystem.kt` | `MotionLevel`, `LocalMotionLevel`, detección, duraciones |
| `ui/theme/ExpressiveAnimations.kt` | Transiciones adaptativas parametrizadas |
| `ui/theme/PackForgeTheme.kt` | Publica `LocalMotionLevel` (colores intactos) |
| `ui/components/AnimationModifiers.kt` | `bounceClick` y `shimmerLoading` adaptativos |
| `data/ThemePreferences.kt` | Nuevo campo `reduceMotion` |
| `ui/viewmodel/ThemeViewModel.kt` | Persistencia `reduce_motion` en DataStore + caché SP |
| `ui/screens/ThemeSettingsScreen.kt` | Interruptor "Reducir Movimiento" con nivel real |

---

## 🏗️ Fase 2 — StudioScreen y Biblioteca (IMPLEMENTADO)

> **Archivos**: `ui/theme/ExpressiveDesign.kt` (nuevo),
> `ui/screens/StudioScreen.kt`, `ui/components/AnimationModifiers.kt`.

### Tokens Expressive centralizados

`ExpressiveDesign.kt` define la escala oficial del rediseño. Existe aparte de
`Spacing.kt` para **no alterar** los valores que ya usan las demás pantallas:

| Grupo | Valores |
|-------|---------|
| `ExpressiveShapes` | icono 18dp · chip 20dp · tarjeta 24dp · héroe 28/28/20/20dp · portada 20dp · acción 14dp · diálogo 28dp |
| `ExpressiveElevation` | tarjeta 2dp · pulsada 1dp · elevada 4dp · diálogo 12dp · barra 3dp · FAB 6dp |
| `ExpressiveSpacing` | contenido 14dp · héroe 18dp · hueco de lista 14dp · margen 16dp |

La forma del héroe es **asimétrica** (28dp arriba, 20dp abajo), el rasgo más
identificable de Expressive. La tarjeta se queda en 24dp —y no en 28dp— porque
en una tarjeta compacta de lista, 28dp muerde demasiada superficie útil.

### Gestos: hundimiento en lugar de salto

Al pulsar, la elevación **baja** de 2dp a 1dp en vez de subir. `bounceClick`
ahora acepta `onPressChanged`, un callback opcional que informa del estado de
pulsación; así la tarjeta puede reaccionar sin duplicar la detección de gestos.
El parámetro tiene valor por defecto, de modo que **las ~50 llamadas existentes
siguen compilando sin cambios**.

### Hápticos Expressive

`rememberExpressiveHaptics()` expone cuatro niveles: `light()` (navegar),
`medium()` (compartir), `heavy()` (confirmar borrado) y `reject()` (acción
destructiva). Usa `View.performHapticFeedback` con las constantes de Android
porque `HapticFeedbackType` de Compose solo ofrece `LongPress` y
`TextHandleMove`. `REJECT` se sustituye por `LONG_PRESS` por debajo de Android 11.

Los hápticos **no dependen** del `MotionLevel`: "Reducir Movimiento" gobierna
animaciones, no vibración. Quien no quiera vibración la desactiva en Ajustes del
sistema, y `performHapticFeedback` lo respeta solo.

### Grilla responsiva

La biblioteca pasa de `LazyColumn` (una tarjeta por fila) a
`LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 340.dp))`:

| Ancho de pantalla | Columnas | Resultado |
|---|---|---|
| Móvil (360–430dp) | 1 | Idéntico a antes: tarjeta horizontal a ancho completo |
| Plegable / tablet pequeña (≥680dp) | 2 | Dos tarjetas lado a lado |
| Tablet (≥1020dp) | 3 | Aprovecha el espacio en vez de estirar |

Cero ramas por tipo de dispositivo: `Adaptive` deduce las columnas del ancho
disponible. En móvil el resultado visual es el mismo que antes, así que no hay
regresión para el caso más común.

Los elementos usan `Modifier.animateItem()` para entrada y reordenamiento, que
se omite cuando el movimiento está reducido.

### Optimización de la caché de portadas

La grilla usa `LazyVerticalGrid` con `key = { it.id }`, así que al filtrar o
reordenar Compose reutiliza los nodos por clave y Coil no recarga las portadas
que ya están en memoria.

### Bug corregido

`DirectCardAction` combinaba `Surface(onClick = ...)` **y** `bounceClick { onClick() }`,
por lo que la acción se ejecutaba **dos veces por toque**. Ahora el modificador
solo aporta la compresión y el háptico, y el `onClick` vive únicamente en la
`Surface`. Esto afectaba a Editar, Actualizar y Regenerar de cada tarjeta.

---

## 🚀 Próximos Pasos

1. ✅ **Fase 1 — Tema + Sistema de Movimiento** (hecho)
2. ✅ **Fase 2 — StudioScreen y biblioteca responsiva** (hecho)
3. **Fase 3 — ImportScreen / McpedlSearch**: `LazyVerticalGrid` adaptativo
4. **Fase 4 — Diálogos**: `fadeSlideScaleIn/Out` en los alerts existentes
5. **Fase 5 — ExportSetup y Conflicts**: unificar formas y hápticos

---

## 📚 Referencias Material 3 Expressive

- **Guía oficial**: https://m3.material.io/styles/typography/expressive
- **Typography scales**: https://m3.material.io/styles/typography/type-scale-tokens
- **Shape system**: https://m3.material.io/styles/shape/shape-scale-tokens
- **Motion guidance**: https://m3.material.io/styles/motion/overview
- **Jetpack Compose M3**: https://developer.android.com/jetpack/compose/designsystems/material3
- **Reduce motion (accesibilidad)**: https://developer.android.com/guide/topics/ui/accessibility/apps#motion

---

**Autor**: Análisis para PackForge  
**Fecha**: 2025-01-05  
**Versión**: 1.1 - Fase 1 implementada (Reduce Motion + niveles por dispositivo)
