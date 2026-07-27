# PackForge

**PackForge** es una app para Android que combina múltiples addons de Minecraft Bedrock Edition en un solo modpack funcional, listo para importar y jugar.

Si alguna vez intentaste juntar dos o más addons a mano y terminaste con texturas rotas, bloques que desaparecen o el juego crasheando al cargar el mundo — para eso existe PackForge.

---

## ¿Qué hace?

- Toma varios archivos `.mcaddon` / `.zip` de addons de Minecraft Bedrock.
- Fusiona de verdad su contenido (bloques, ítems, texturas, sonidos, recetas, entidades, etc.), no solo los comprime juntos.
- Resuelve conflictos entre addons (por ejemplo, cuando dos addons modifican el mismo bloque o usan el mismo nombre de textura) y te avisa qué se resolvió y cómo.
- Genera un único archivo `.mcaddon` final, listo para abrir directamente con Minecraft.

## ¿Por qué no simplemente uso otra herramienta o lo hago manual?

Fusionar addons a mano implica editar manifests, resolver IDs duplicados, unir archivos JSON sin romper la sintaxis, y vincular correctamente el behavior pack con el resource pack. Un solo error en cualquiera de esos pasos hace que Minecraft rechace el paquete o cargue el mundo con errores. PackForge automatiza todo ese proceso.

## Cómo usarla

1. Abre PackForge en tu dispositivo Android.
2. Selecciona los addons (`.mcaddon` o `.zip`) que quieres combinar.
3. Inicia la fusión.
4. Revisa el reporte de conflictos (si los hubo) — te muestra exactamente qué se resolvió.
5. Exporta el `.mcaddon` resultante y ábrelo con Minecraft para importarlo.

## Requisitos

- Android 12 o superior.
- Addons de Minecraft Bedrock Edition compatibles con el formato estándar (con `manifest.json`).

## Estado del proyecto

Este es un proyecto personal en desarrollo activo. Puede contener errores o casos de addons no soportados todavía. No está disponible en Google Play.

## Notas

- PackForge no modifica el contenido creativo de los addons originales, solo los combina.
- Si dos addons son incompatibles entre sí de forma fundamental (por ejemplo, requieren versiones de Minecraft distintas), la fusión puede generar advertencias — revisa siempre el log de conflictos antes de jugar.

---

*Proyecto personal, no afiliado a Mojang ni a Microsoft.*
