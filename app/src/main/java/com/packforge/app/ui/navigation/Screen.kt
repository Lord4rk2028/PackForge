package com.packforge.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Publish
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Publish
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val iconFilled: ImageVector,
    val iconOutlined: ImageVector
) {
    object Import : Screen(
        route = "import",
        title = "Importar",
        iconFilled = Icons.Filled.Download,
        iconOutlined = Icons.Outlined.Download
    )
    
    object Conflicts : Screen(
        route = "conflicts",
        title = "Conflictos",
        iconFilled = Icons.Filled.Warning,
        iconOutlined = Icons.Outlined.Warning
    )
    
    object Export : Screen(
        route = "export",
        title = "Exportar",
        iconFilled = Icons.Filled.Publish,
        iconOutlined = Icons.Outlined.Publish
    )
    
    object Studio : Screen(
        route = "studio",
        title = "Studio",
        iconFilled = Icons.Filled.AutoAwesome,
        iconOutlined = Icons.Outlined.AutoAwesome
    )
}

fun getScreenFromRoute(route: String?): Screen {
    return when (route) {
        Screen.Import.route -> Screen.Import
        Screen.Conflicts.route -> Screen.Conflicts
        Screen.Export.route -> Screen.Export
        Screen.Studio.route -> Screen.Studio
        else -> Screen.Import
    }
}
