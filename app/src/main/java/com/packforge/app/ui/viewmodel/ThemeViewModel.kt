package com.packforge.app.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.packforge.app.data.ThemePreferences
import com.packforge.app.util.PackForgeConfig
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_preferences")

private val DARK_MODE_KEY = booleanPreferencesKey("dark_mode")
private val AMOLED_MODE_KEY = booleanPreferencesKey("amoled_mode")
private val ACCENT_HEX_KEY = stringPreferencesKey("accent_hex")
private val VIVID_COLORS_KEY = booleanPreferencesKey("vivid_colors")
private val EXPRESSIVE_MOTION_KEY = booleanPreferencesKey("expressive_motion")
private val VERBOSE_FILE_LOGS_KEY = booleanPreferencesKey("verbose_file_logs")

class ThemeViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStore = application.themeDataStore

    private fun Preferences.toThemePreferences(): ThemePreferences {
        val verbose = this[VERBOSE_FILE_LOGS_KEY] ?: false
        PackForgeConfig.verboseFileLogs = verbose
        return ThemePreferences(
            darkMode = this[DARK_MODE_KEY] ?: true,
            amoledMode = this[AMOLED_MODE_KEY] ?: false,
            accentHex = this[ACCENT_HEX_KEY] ?: "#2ECC71",
            vividColors = this[VIVID_COLORS_KEY] ?: true,
            expressiveMotion = this[EXPRESSIVE_MOTION_KEY] ?: true,
            verboseFileLogs = verbose
        )
    }

    private val _preferences = MutableStateFlow(
        runBlocking {
            try {
                withTimeout(50) { dataStore.data.first().toThemePreferences() }
            } catch (e: Exception) {
                ThemePreferences()
            }
        }
    )
    val preferences: StateFlow<ThemePreferences> = _preferences.asStateFlow()

    init {
        viewModelScope.launch {
            dataStore.data.collect { prefs ->
                _preferences.value = prefs.toThemePreferences()
            }
        }
    }

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
