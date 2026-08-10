package com.packforge.app.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.packforge.app.data.AccentColor
import com.packforge.app.data.ThemePreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_preferences")

private val DARK_MODE_KEY = booleanPreferencesKey("dark_mode")
private val ACCENT_COLOR_KEY = stringPreferencesKey("accent_color")
private val EXPRESSIVE_MOTION_KEY = booleanPreferencesKey("expressive_motion")

class ThemeViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStore = application.themeDataStore

    // Estado reactivo de las preferencias de tema (leído de DataStore)
    val preferences: StateFlow<ThemePreferences> = dataStore.data
        .map { prefs ->
            ThemePreferences(
                darkMode = prefs[DARK_MODE_KEY] ?: true,
                accentColor = prefs[ACCENT_COLOR_KEY]?.let { name ->
                    runCatching { AccentColor.valueOf(name) }.getOrNull()
                } ?: AccentColor.EMERALD,
                expressiveMotion = prefs[EXPRESSIVE_MOTION_KEY] ?: true
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ThemePreferences()
        )

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[DARK_MODE_KEY] = enabled }
        }
    }

    fun setAccentColor(color: AccentColor) {
        viewModelScope.launch {
            dataStore.edit { it[ACCENT_COLOR_KEY] = color.name }
        }
    }

    fun setExpressiveMotion(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[EXPRESSIVE_MOTION_KEY] = enabled }
        }
    }
}