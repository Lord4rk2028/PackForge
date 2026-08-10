package com.packforge.app.ui.viewmodel

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesKeys
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.rx.preferences
import androidx.datastore.preferences.rx.edit
import androidx.datastore.rx.RxDataStore
import androidx.datastore.rx.RxDataStoreBuilder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.packforge.app.data.AccentColor
import com.packforge.app.data.ThemePreferences
import io.reactivex.rxjava3.core.Flowable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.Schedulers
import com.packforge.app.data.ThemePreferences
import com.packforge.app.data.AccentColor
import androidx.datastore.preferences.core.PreferencesKeys
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.rx.preferences
import androidx.datastore.rx.RxDataStore
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import com.packforge.app.data.ThemePreferences
import com.packforge.app.data.AccentColor

class ThemeViewModel(
    private val context: Context
) : ViewModel() {

    private val dataStore: RxDataStore<Preferences> = RxDataStoreBuilder(
        context = context,
        name = "theme_preferences"
    ).build()

    // Keys para DataStore
    private val DARK_MODE_KEY = PreferencesKeys.boolean("dark_mode")
    private val ACCENT_COLOR_KEY = PreferencesKeys.string("accent_color")
    private val EXPRESSIVE_MOTION_KEY = PreferencesKeys.boolean("expressive_motion")

    // Estado reactivo
    private val _preferences = MutableStateFlow(ThemePreferences())
    val preferences = _preferences.distinctUntilChanged()

    init {
        loadPreferences()
    }

    private fun loadPreferences() {
        viewModelScope.launch {
            dataStore.data
                .map { prefs ->
                    ThemePreferences(
                        darkMode = prefs[DARK_MODE_KEY] ?: true,
                        accentColor = AccentColor.valueOf(prefs[ACCENT_COLOR_KEY] ?: "EMERALD"),
                        expressiveMotion = prefs[EXPRESSIVE_MOTION_KEY] ?: true
                    )
                }
                .collect { _preferences.value = it }
        }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        dataStore.rxUpdateData { prefs ->
            prefs.toMutablePreferences().apply { put(DARK_MODE_KEY, enabled) }
        }
    }

    suspend fun setAccentColor(color: AccentColor) {
        dataStore.rxUpdateData { prefs ->
            prefs.toMutablePreferences().apply { put(ACCENT_COLOR_KEY, color.name) }
        }
    }

    suspend fun setExpressiveMotion(enabled: Boolean) {
        dataStore.rxUpdateData { prefs ->
            prefs.toMutablePreferences().apply { put(EXPRESSIVE_MOTION_KEY, enabled) }
        }
    }
}
