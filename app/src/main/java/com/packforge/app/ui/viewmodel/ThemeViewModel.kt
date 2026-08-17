package com.packforge.app.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.packforge.app.data.ThemePreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_preferences")

private val DARK_MODE_KEY = booleanPreferencesKey("dark_mode")
private val AMOLED_MODE_KEY = booleanPreferencesKey("amoled_mode")
private val ACCENT_HEX_KEY = stringPreferencesKey("accent_hex")
private val VIVID_COLORS_KEY = booleanPreferencesKey("vivid_colors")
private val EXPRESSIVE_MOTION_KEY = booleanPreferencesKey("expressive_motion")
private val VERBOSE_FILE_LOGS_KEY = booleanPreferencesKey("verbose_file_logs")

class ThemeViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStore = application.themeDataStore

    val preferences: StateFlow<ThemePreferences> = dataStore.data
        .map { prefs ->
            val verbose = prefs[VERBOSE_FILE_LOGS_KEY] ?: false
            // Sincronizar el valor con el objeto de configuración global de manera inmediata
            com.packforge.app.util.PackForgeConfig.verboseFileLogs = verbose
            ThemePreferences(
                darkMode = prefs[DARK_MODE_KEY] ?: true,
                amoledMode = prefs[AMOLED_MODE_KEY] ?: false,
                accentHex = prefs[ACCENT_HEX_KEY] ?: "#2ECC71",
                vividColors = prefs[VIVID_COLORS_KEY] ?: true,
                expressiveMotion = prefs[EXPRESSIVE_MOTION_KEY] ?: true,
                verboseFileLogs = verbose
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ThemePreferences()
        )

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[DARK_MODE_KEY] = enabled } }
    }

    fun setAmoledMode(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[AMOLED_MODE_KEY] = enabled } }
    }

    fun setAccentHex(hex: String) {
        viewModelScope.launch { dataStore.edit { it[ACCENT_HEX_KEY] = hex } }
    }

    fun setVividColors(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[VIVID_COLORS_KEY] = enabled } }
    }

    fun setExpressiveMotion(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[EXPRESSIVE_MOTION_KEY] = enabled } }
    }

    fun setVerboseFileLogs(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[VERBOSE_FILE_LOGS_KEY] = enabled } }
    }
}
