package com.packforge.app.domain.engine

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Buffer de memoria para evitar I/O redundante durante la fusión.
 * Mantiene objetos JSON en RAM hasta el volcado final.
 */
object MemoryMergeBuffer {
    private val buffer = ConcurrentHashMap<String, JSONObject>()

    fun put(path: String, json: JSONObject) {
        buffer[path] = json
    }

    fun get(path: String): JSONObject? = buffer[path]

    fun getAll(): Map<String, JSONObject> = buffer

    fun contains(path: String): Boolean = buffer.containsKey(path)

    fun clear() {
        buffer.clear()
    }
}
