# Tareas de Optimización de Rendimiento

- `[/]` **Fase 1: Optimización de Imágenes**
  - `[ ]` Reforzar `CachedAsyncImage.kt` con políticas de caché explícitas.
  - `[ ]` Reemplazar `AsyncImage` sueltos en `ExportSetupScreen.kt` por `CachedAsyncImage`.
- `[ ]` **Fase 2: Optimización de LazyColumn/LazyRow**
  - `[ ]` Garantizar `key` y `contentType` estables en todas las listas de `ImportScreen.kt`.
  - `[ ]` Garantizar `key` y `contentType` estables en todas las listas de `StudioScreen.kt`.
  - `[ ]` Garantizar `key` y `contentType` estables en todas las listas de `ConflictsScreen.kt`.
- `[ ]` **Fase 3: Reducir Recomposiciones**
  - `[ ]` Memorizar todos los callbacks pasados a ítems de lista usando `remember`.
  - `[ ]` Extraer y aislar sub-composables para reducir áreas de recomposición.
- `[ ]` **Fase 4: Optimizaciones Generales & Navegación**
  - `[ ]` Configurar transiciones animadas en `NavHost` (`MainActivity.kt`).
  - `[ ]` Reemplazar `Modifier.background` por `Surface` donde corresponda.
  - `[ ]` Verificar compilación exitosa sin errores.
