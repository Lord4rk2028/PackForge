# Task List - PackForge Performance Optimization

## Fase 1: Imágenes
- [ ] Implementar CachedAsyncImage.kt
- [ ] Reemplazar AsyncImage en ExportSetupScreen con CachedAsyncImage
- [ ] Reemplazar AsyncImage en ModrinthSearchScreen con CachedAsyncImage
- [ ] Reemplazar AsyncImage en CoverPickerSection con CachedAsyncImage

## Fase 2: LazyColumn Optimization
- [ ] Agregar key/contentType/beyondBoundsItemCount a ExportSetupScreen LazyColumn
- [ ] Agregar key/contentType/beyondBoundsItemCount a ModrinthSearchScreen LazyColumn
- [ ] Verificar StudioScreen LazyColumns

## Fase 3: Reducir Recomposiciones
- [ ] ExportSetupScreen: derivedStateOf + remember para cálculos costosos
- [ ] StudioScreen: extraer subcomposables (SavedModpackCard, StudioCard)
- [ ] Evitar lambdas nuevas en recomposición

## Fase 4: General
- [ ] Reemplazar logs Log.d/Log.e con PackForgeLog en todos los archivos
- [ ] Reemplazar Modifier.shadow con Surface elevation
- [ ] Verificar compilación

## Verificación
- [ ] Compilar sin errores
- [ ] Lista de archivos modificados