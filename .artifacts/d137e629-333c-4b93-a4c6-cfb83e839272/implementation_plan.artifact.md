# Plan de Optimización de Rendimiento (PackForge)

Este plan detalla las acciones para resolver los problemas de lag y stuttering reportados, siguiendo las 4 fases de optimización solicitadas.

## Cambios Propuestos

### Fase 1: Optimización de Imágenes
*   **[MODIFY] [CachedAsyncImage.kt](file:///C:/Users/HP/AndroidStudioProjects/PackForge/app/src/main/java/com/packforge/app/ui/components/CachedAsyncImage.kt)**: Reforzar la configuración de Coil con políticas de caché explícitas y `crossfade`.
*   **[MODIFY] [ExportSetupScreen.kt](file:///C:/Users/HP/AndroidStudioProjects/PackForge/app/src/main/java/com/packforge/app/ui/screens/ExportSetupScreen.kt)**: Reemplazar usos directos de `AsyncImage` por `CachedAsyncImage` para asegurar consistencia en el cacheo.

### Fase 2: Optimización de LazyColumn/LazyRow
*   **[MODIFY] [ImportScreen.kt](file:///C:/Users/HP/AndroidStudioProjects/PackForge/app/src/main/java/com/packforge/app/ui/screens/ImportScreen.kt)**: Verificar y asegurar que todos los `items` tengan `key` y `contentType`.
*   **[MODIFY] [StudioScreen.kt](file:///C:/Users/HP/AndroidStudioProjects/PackForge/app/src/main/java/com/packforge/app/ui/screens/StudioScreen.kt)**: Asegurar el uso de `key` y `contentType` en las listas de modpacks.
*   **[MODIFY] [ConflictsScreen.kt](file:///C:/Users/HP/AndroidStudioProjects/PackForge/app/src/main/java/com/packforge/app/ui/screens/ConflictsScreen.kt)**: Optimizar el renderizado de la lista de conflictos.

### Fase 3: Reducción de Recomposiciones
*   **Refactorización de Componentes**: Extraer sub-composables en `AddonCard`, `ConflictCard`, `StudioCard` y `SavedModpackCard` para aislar recomposiciones.
*   **Memorización de Lambdas**: Usar `remember` para todos los callbacks pasados a componentes de lista para evitar que se consideren "inestables" por ser lambdas nuevas en cada recomposición.
*   **Uso de `derivedStateOf`**: Asegurar que los cálculos complejos (filtros, conteos) estén envueltos en `derivedStateOf`.

### Fase 4: Optimizaciones Generales
*   **[MODIFY] [MainActivity.kt](file:///C:/Users/HP/AndroidStudioProjects/PackForge/app/src/main/java/com/packforge/app/MainActivity.kt)**: Implementar transiciones suaves (`fadeIn`, `fadeOut`, `slideIn`, `slideOut`) en el `NavHost`.
*   **Uso de `Surface`**: Reemplazar `Modifier.background` por `Surface` con color de tema donde sea posible para optimizar el dibujo de fondos.
*   **Limpieza de Logs**: Verificar que `PackForgeLog` desactive efectivamente los logs en producción (ya implementado, pero se revisará su uso).

## Plan de Verificación

### Pruebas Manuales
1.  **Scroll Fluido**: Deslizar rápidamente las listas en `ImportScreen` y `StudioScreen` para verificar la ausencia de "jank".
2.  **Navegación**: Cambiar entre pestañas de la barra de navegación inferior y observar las transiciones suaves.
3.  **Caché de Imágenes**: Deslizar una lista larga de addons, salir de la pantalla y volver a entrar; las imágenes deberían aparecer instantáneamente si están en caché.
4.  **Compilación**: Asegurar que el proyecto compila correctamente después de todas las refactorizaciones.
