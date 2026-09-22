package com.packforge.app.util

import android.util.Log
import com.packforge.app.BuildConfig

object PackForgeLog {

    /**
     * Activado únicamente en builds de depuración.
     *
     * Antes era un flag fijo (`true`) que se olvidaba cambiar antes de publicar:
     * en release se seguían emitiendo logs. `BuildConfig.DEBUG` lo resuelve solo
     * (false en release, true en debug) sin intervención manual.
     */
    private val ENABLE_DEBUG = BuildConfig.DEBUG

    fun d(tag: String, message: String) {
        if (ENABLE_DEBUG) {
            Log.d(tag, message)
        }
    }

    fun v(tag: String, message: String) {
        if (ENABLE_DEBUG) {
            Log.v(tag, message)
        }
    }

    fun i(tag: String, message: String) {
        if (ENABLE_DEBUG) {
            Log.i(tag, message)
        }
    }

    fun w(tag: String, message: String) {
        if (ENABLE_DEBUG) {
            Log.w(tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (ENABLE_DEBUG) {
            if (throwable != null) {
                Log.e(tag, message, throwable)
            } else {
                Log.e(tag, message)
            }
        }
    }
}