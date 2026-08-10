package com.packforge.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.packforge.app.R

/**
 * Sitios web de donde se pueden obtener addons Bedrock.
 * Mantiene la clave (`sourceKey`) que usan ViewModel/MainActivity
 * para persistir la última URL y el WebView por fuente.
 */
enum class AddonSite(
    val sourceKey: String,
    val displayName: String,
    val url: String,
    val logoUrl: String,
    val logoRes: Int,
    val fallbackColor: Color
) {
    MCPEDL(
        sourceKey = "MCPEDL",
        displayName = "MCPEDL",
        url = "https://mcpedl.com",
        logoUrl = "https://mcpedl.com/favicon.ico",
        logoRes = R.drawable.logo_mcpedl,
        fallbackColor = Color(0xFF4CAF50)
    ),
    CURSEFORGE(
        sourceKey = "CurseForge",
        displayName = "CurseForge",
        url = "https://www.curseforge.com/minecraft-bedrock/addons",
        logoUrl = "https://www.curseforge.com/favicon.ico",
        logoRes = R.drawable.logo_curseforge,
        fallbackColor = Color(0xFFF16436)
    ),
    MODBAY(
        sourceKey = "ModBay",
        displayName = "Modbay",
        url = "https://modbay.org",
        logoUrl = "https://modbay.org/favicon.ico",
        logoRes = R.drawable.logo_modbay,
        fallbackColor = Color(0xFF2196F3)
    );

    /** URL de inicio (listado de addons) al abrir el sitio por primera vez. */
    val browseUrl: String
        get() = when (this) {
            MCPEDL -> "https://mcpedl.com/category/mods-addons/"
            CURSEFORGE -> "https://www.curseforge.com/minecraft-bedrock"
            MODBAY -> "https://modbay.org/mods/"
        }

    companion object {
        /** Resuelve el sitio a partir de la clave que usa el ViewModel. */
        fun fromSourceKey(key: String?): AddonSite =
            entries.firstOrNull { it.sourceKey == key } ?: MCPEDL
    }
}

/**
 * Selector de sitios con sus logos oficiales (favicon con Coil y
 * fallback a vector drawable + color si no se puede cargar).
 */
@Composable
fun SiteSelector(
    currentSite: AddonSite = AddonSite.MCPEDL,
    onSelect: (AddonSite) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = modifier.fillMaxWidth()
    ) {
        AddonSite.entries.forEach { site ->
            FilterChip(
                selected = site == currentSite,
                onClick = { onSelect(site) },
                leadingIcon = {
                    AsyncImage(
                        model = site.logoUrl,
                        contentDescription = site.displayName,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(site.fallbackColor.copy(alpha = 0.25f)),
                        placeholder = ColorPainter(site.fallbackColor),
                        error = painterResource(site.logoRes)
                    )
                },
                label = { Text(site.displayName) }
            )
        }
    }
}