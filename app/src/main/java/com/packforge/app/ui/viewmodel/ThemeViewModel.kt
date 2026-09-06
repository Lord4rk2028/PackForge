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

private const val SP_THEME_CACHE = "theme_preferences_cache"

private val DARK_MODE_KEY = booleanPreferencesKey("dark_mode")
private val AMOLED_MODE_KEY = booleanPreferencesKey("amoled_mode")
private val ACCENT_HEX_KEY = stringPreferencesKey("accent_hex")
private val VIVID_COLORS_KEY = booleanPreferencesKey("vivid_colors")
private val EXPRESSIVE_MOTION_KEY = booleanPreferencesKey("expressive_motion")
private val VERBOSE_FILE_LOGS_KEY = booleanPreferencesKey("verbose_file_logs")

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

private fun loadCachedPreferences(context: Context, dataStore: DataStore<Preferences>): ThemePreferences {
    val sp = context.getSharedPreferences(SP_THEME_CACHE, Context.MODE_PRIVATE)
    if (!sp.contains("dark_mode") && !sp.contains("accent_hex")) {
        return runBlocking {
            try {
                withTimeout(1000) { dataStore.data.first().toThemePreferences() }
            } catch (e: Exception) {
                ThemePreferences()
            }
        }
    }
    val verbose = sp.getBoolean("verbose_file_logs", false)
    PackForgeConfig.verboseFileLogs = verbose
    return ThemePreferences(
        darkMode = sp.getBoolean("dark_mode", true),
        amoledMode = sp.getBoolean("amoled_mode", false),
        accentHex = sp.getString("accent_hex", "#2ECC71") ?: "#2ECC71",
        vividColors = sp.getBoolean("vivid_colors", true),
        expressiveMotion = sp.getBoolean("expressive_motion", true),
        verboseFileLogs = verbose
    )
}

private fun saveCachedPreferences(context: Context, prefs: ThemePreferences) {
    context.getSharedPreferences(SP_THEME_CACHE, Context.MODE_PRIVATE).edit()
        .putBoolean("dark_mode", prefs.darkMode)
        .putBoolean("amoled_mode", prefs.amoledMode)
        .putString("accent_hex", prefs.accentHex)
        .putBoolean("vivid_colors", prefs.vividColors)
        .putBoolean("expressive_motion", prefs.expressiveMotion)
        .putBoolean("verbose_file_logs", prefs.verboseFileLogs)
        .apply()
}

class ThemeViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStore = application.themeDataStore

    private val _preferences = MutableStateFlow(loadCachedPreferences(application, dataStore))
    val preferences: StateFlow<ThemePreferences> = _preferences.asStateFlow()

    init {
        viewModelScope.launch {
            dataStore.data.collect { prefs ->
                val themePrefs = prefs.toThemePreferences()
                _preferences.value = themePrefs
                saveCachedPreferences(application, themePrefs)
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

/**
 * Acceso puntual al color de acento definido por el usuario para componentes
 * NO-Compose (ej. notificación del servicio de fusión). Usa el MISMO DataStore o Caché SP.
 */
object ThemeAccent {
    private const val DEFAULT_HEX = "#2ECC71"

    suspend fun hex(context: Context): String = try {
        val sp = context.getSharedPreferences(SP_THEME_CACHE, Context.MODE_PRIVATE)
        sp.getString("accent_hex", null) ?: context.themeDataStore.data.first()[ACCENT_HEX_KEY] ?: DEFAULT_HEX
    } catch (_: Exception) { DEFAULT_HEX }

    /** Lectura síncrona instantánea pensada para onStartCommand del servicio. */
    fun colorBlocking(context: Context): Int {
        val sp = context.getSharedPreferences(SP_THEME_CACHE, Context.MODE_PRIVATE)
        val hexStr = sp.getString("accent_hex", null)
        if (hexStr != null) {
            try {
                return android.graphics.Color.parseColor(hexStr.trim())
            } catch (_: Exception) {}
        }
        return runBlocking {
            try {
                android.graphics.Color.parseColor(hex(context).trim())
            } catch (_: Exception) {
                0xFF2ECC71.toInt()
            }
        }
    }
}
