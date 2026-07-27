# Implement Clickable Cover Image on Export Screen

Make the large cover preview box clickable so users can select a modpack cover image directly from it, and remove the thin "Subir imagen de portada" button underneath.

## User Review Required

> [!NOTE]
> The large card preview box (`Box`) will become interactive via `.clickable { coverPickerLauncher.launch("image/*") }`, providing a better and cleaner UX aligned with modern app design.

## Proposed Changes

### Export Setup Screen

#### [MODIFY] [ExportSetupScreen.kt](file:///C:/Users/HP/AndroidStudioProjects/PackForge/app/src/main/java/com/packforge/app/ui/screens/ExportSetupScreen.kt)

- Add `.clickable { coverPickerLauncher.launch("image/*") }` to the large cover preview `Box` container.
- Remove the thin `FilledTonalButton` ("Subir imagen de portada") that sits right below the cover preview box.

## Verification Plan

### Automated Tests
- None needed (UI/UX layout modification).

### Manual Verification
- Deploy/run the app and navigate to the Export screen.
- Verify that clicking the large cover image box opens the image picker.
- Verify that the thin button below has been successfully removed.
