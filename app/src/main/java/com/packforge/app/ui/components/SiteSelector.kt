package com.packforge.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

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
    val fallbackColor: Color
) {
    MCPEDL(
        sourceKey = "MCPEDL",
        displayName = "MCPEDL",
        url = "https://mcpedl.com",
        // Favicon REAL del sitio (servicio de favicons de Google).
        logoUrl = "https://www.google.com/s2/favicons?domain=mcpedl.com&sz=64",
        fallbackColor = Color(0xFF4CAF50)
    ),
    CURSEFORGE(
        sourceKey = "CurseForge",
        displayName = "CurseForge",
        url = "https://www.curseforge.com/minecraft-bedrock/addons",
        logoUrl = "https://www.google.com/s2/favicons?domain=curseforge.com&sz=64",
        fallbackColor = Color(0xFFF16436)
    ),
    MODBAY(
        sourceKey = "ModBay",
        displayName = "ModBay",
        url = "https://modbay.org",
        logoUrl = "https://www.google.com/s2/favicons?domain=modbay.org&sz=64",
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
 * Selector de sitios con sus LOGOS OFICIALES (favicon real de cada web).
 * Tarjetas de ancho igual, espaciadas y con el seleccionado resaltado.
 */
@Composable
fun SiteSelector(
    currentSite: AddonSite = AddonSite.MCPEDL,
    onSelect: (AddonSite) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AddonSite.entries.forEach { site ->
            SiteCard(
                site = site,
                selected = site == currentSite,
                onClick = { onSelect(site) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SiteCard(
    site: AddonSite,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = tween(220),
        label = "siteContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected)
            MaterialTheme.colorScheme.primary
        else
            MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(220),
        label = "siteBorder"
    )

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        shadowElevation = if (selected) 4.dp else 1.dp,
        modifier = modifier.clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AsyncImage(
                model = site.logoUrl,
                contentDescription = "Logo de ${site.displayName}",
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(site.fallbackColor.copy(alpha = 0.2f))
                    .border(
                        1.dp,
                        site.fallbackColor.copy(alpha = 0.35f),
                        CircleShape
                    ),
                placeholder = ColorPainter(site.fallbackColor.copy(alpha = 0.35f))
            )
            Text(
                text = site.displayName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (selected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}