package com.packforge.app.util

import android.util.Log

object PackForgeLog {

    private const val ENABLE_DEBUG = true // Cambiar a false en release

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