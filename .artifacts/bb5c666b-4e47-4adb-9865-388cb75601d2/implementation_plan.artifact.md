# Plan de Corrección para ExportSetupScreen.kt

Resolver los errores de compilación en `ExportSetupScreen.kt`:
1. **Línea 823**: Uso erróneo de `item { ... }` fuera del bloque `LazyColumn` (dentro del diálogo AlertDialog).
2. **Línea 884**: Uso incorrecto de `Icon` pasando `Icons.Default.Warning` (que es `ImageVector`) a parámetros de tipo `ImageBitmap` o `Painter` o sin `contentDescription`.
3. **Línea 1008**: Bloque `try-catch` alrededor de llamadas a funciones Composable al leer el ZIP.
4. **Línea 1128**: `TextButton` no importado o no reconocido.
5. **Línea 1129**: Invocación de `@Composable` fuera del contexto composable (debido al try-catch incorrecto y estructura del AlertDialog).

## Cambios Propuestos

### [MODIFY] [ExportSetupScreen.kt](file:///C:/Users/HP/AndroidStudioProjects/PackForge/app/src/main/java/com/packforge/app/ui/screens/ExportSetupScreen.kt)
- Importar `androidx.compose.material3.TextButton`.
- Corregir el botón "Debug ZIP" mal ubicado dentro del `LazyColumn` (moverlo al lugar correcto o quitar el `item { ... }` duplicado).
- Corregir las llamadas a `Icon` en la línea 884 (asegurar uso correcto de `imageVector = Icons.Default.Warning` con `contentDescription = null`).
- Mover toda la lógica de lectura de archivos e InputStream (ZipInputStream, org.json.JSONObject, etc.) **fuera** de las llamadas Composable, calculando los estados o variables antes de invocar la interfaz, evitando por completo llamadas Composable dentro de bloques `try-catch`.

## Plan de Verificación
- Compilar el proyecto con `gradle_build(":app:compileDebugKotlin")`.
