# Plan de Optimización de Rendimiento - PackForge

## Objetivo
Eliminar el lag y stuttering al deslizar entre pantallas y dentro de listas, haciendo que la app se sienta fluida y responsiva (60fps).

## Diagnóstico Actual
- Coil ya está agregado al `build.gradle.kts` ✅
- `PackForgeApplication` ya implementa `ImageLoaderFactory` con caché ✅
- `CachedAsyncImage` existe pero está vacía (0 bytes)
- Las pantallas `ImportScreen`, `ConflictsScreen`, `ExportSetupScreen`, `StudioScreen` usan `LazyColumn`
- `StudioScreen` ya tiene `key = { it.id }` y `beyondBoundsItemCount = 3` en algunos `LazyColumn`
- `ImportScreen` ya tiene `key`, `contentType`, y `beyondBoundsItemCount`
- `ConflictsScreen` ya tiene `key`, `contentType`, y `beyondBoundsItemCount`
- `Addon` ya tiene `@Immutable` ✅
- Muchos `Log.d/Log.e` están presentes en producción
- `ExportSetupScreen` y `ModrinthSearchScreen` usan `AsyncImage` directamente sin caché óptima
- `StudioScreen` usa `CachedAsyncImage` para portadas

## FASE 1: IMAGENES - Completar CachedAsyncImage y optimizar carga
- [ ] Implementar `CachedAsyncImage.kt` como wrapper de Coil con caché personalizada
- [ ] Reemplazar `AsyncImage` directo en `ExportSetupScreen` con `CachedAsyncImage`
- [ ] Reemplazar `AsyncImage` directo en `ModrinthSearchScreen` con `CachedAsyncImage`
- [ ] Reemplazar `AsyncImage` directo en `CoverPickerSection` con `CachedAsyncImage`

## FASE 2: LAZYCOLUMN OPTIMIZATION - Completar missing keys + contentType
- [ ] `ExportSetupScreen` LazyColumn: agregar `key`, `contentType`, `beyondBoundsItemCount = 3`
- [ ] `ModrinthSearchScreen` LazyColumn: agregar `key`, `contentType`, `beyondBoundsItemCount = 3`
- [ ] `StudioScreen` MyModpacksScreen LazyColumn: ya tiene `key` y `beyondBoundsItemCount` ✅
- [ ] Verificar que todos los `items()` tengan `key` estable

## FASE 3: REDUCIR RECOMPOSICIONES
- [ ] `ExportSetupScreen`: mover cálculos derivados con `derivedStateOf` + `remember`
- [ ] `StudioScreen`: extraer subcomposables para aislar recomposiciones
- [ ] `ImportScreen`: `AddonCard` ya está como subcomposable separado ✅
- [ ] Evitar lambdas nuevas en cada recomposición (usar `remember` para callbacks)

## FASE 4: GENERAL
- [ ] Reemplazar `Log.d/Log.e` en `PackForgeViewModel` y pantallas con `PackForgeLog`
- [ ] Reemplazar `Modifier.shadow` con `Surface` elevation donde aplique
- [ ] Agregar `drawWithCache` si hay Canvas/drawBehind (verificar)
- [ ] Optimizar navegación con transiciones suaves (ya tiene AnimatedContent ✅)

## Archivos a modificar
1. `ui/components/CachedAsyncImage.kt` - Implementar Componente
2. `ui/screens/ExportSetupScreen.kt` - Coil + keys + derivedStateOf + logs
3. `ui/screens/ModrinthSearchScreen.kt` - Coil + keys + logs
4. `ui/screens/CoverPickerSection.kt` - Coil + keys (en ExportSetupScreen)
5. `ui/screens/StudioScreen.kt` - Subcomposables + logs
6. `ui/viewmodel/PackForgeViewModel.kt` - Reemplazar Log.d/Log.e con PackForgeLog
7. `domain/engine/AddonExtractor.kt` - Reemplazar Log.d/Log.e con PackForgeLog
8. `domain/engine/JsonDeepMerger.kt` - Reemplazar Log.e con PackForgeLog
9. `domain/engine/PackForgeOrchestrator.kt` - Reemplazar Log.d/Log.e con PackForgeLog
10. `ui/screens/ExportSetupScreen.kt` - Linea 1158 tiene Log.e directo
