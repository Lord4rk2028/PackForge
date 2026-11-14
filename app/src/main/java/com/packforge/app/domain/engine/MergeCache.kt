package com.packforge.app.domain.engine

import com.packforge.app.util.PackForgeLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.security.MessageDigest

object MergeCache {

    private const val TAG = "PackForge_MergeCache"
    private const val CACHE_SUBDIR = "merge_cache"
    private const val INDEX_FILE = "index.ser"

    private var cacheDir: File? = null
    private val index = mutableMapOf<String, MergeCacheEntry>()

    fun init(baseDir: File) {
        cacheDir = File(baseDir, CACHE_SUBDIR).apply { mkdirs() }
        loadIndex()
        PackForgeLog.d(TAG, "Caché de fusión inicializado en ${cacheDir?.absolutePath} (${index.size} entrada(s))")
    }

    fun clear() {
        index.clear()
        cacheDir?.let { dir ->
            dir.deleteRecursively()
            dir.mkdirs()
        }
        PackForgeLog.d(TAG, "Caché de fusión limpiado")
    }

    fun invalidate(hash: String) {
        index.remove(hash)
        val entryDir = cacheDir?.let { File(it, hash) }
        entryDir?.deleteRecursively()
        saveIndex()
    }

    fun get(hash: String): MergeCacheEntry? = index[hash]

    fun put(hash: String, entry: MergeCacheEntry) {
        index[hash] = entry
        val entryDir = cacheDir?.let { File(it, hash) } ?: return
        entryDir.deleteRecursively()
        entryDir.mkdirs()

        entry.bpDir?.let { bp ->
            val dest = File(entryDir, "bp")
            copyDirectory(bp, dest)
        }
        entry.rpDir?.let { rp ->
            val dest = File(entryDir, "rp")
            copyDirectory(rp, dest)
        }

        saveIndex()
        PackForgeLog.d(TAG, "Entrada cacheada para hash $hash (BP=${entry.bpDir != null}, RP=${entry.rpDir != null})")
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun quickChanged(file: File, previousSize: Long, previousLastModified: Long): Boolean {
        return file.length() != previousSize || file.lastModified() != previousLastModified
    }

    private fun loadIndex() {
        val indexFile = cacheDir?.let { File(it, INDEX_FILE) } ?: return
        if (!indexFile.exists()) return
        try {
            FileInputStream(indexFile).use { fis ->
                ObjectInputStream(fis).use { ois ->
                    @Suppress("UNCHECKED_CAST")
                    val loaded = ois.readObject() as? Map<String, MergeCacheEntry> ?: return
                    index.clear()
                    index.putAll(loaded)
                }
            }
        } catch (e: Exception) {
            PackForgeLog.w(TAG, "No se pudo cargar el índice del caché: ${e.message}")
        }
    }

    private fun saveIndex() {
        val indexFile = cacheDir?.let { File(it, INDEX_FILE) } ?: return
        try {
            FileOutputStream(indexFile).use { fos ->
                ObjectOutputStream(fos).use { oos ->
                    oos.writeObject(index.toMap())
                }
            }
        } catch (e: Exception) {
            PackForgeLog.w(TAG, "No se pudo guardar el índice del caché: ${e.message}")
        }
    }

    private fun copyDirectory(src: File, dest: File) {
        dest.mkdirs()
        src.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                copyDirectory(file, File(dest, file.name))
            } else {
                FileInputStream(file).channel.use { input ->
                    FileOutputStream(File(dest, file.name)).channel.use { output ->
                        output.transferFrom(input, 0, Long.MAX_VALUE)
                    }
                }
            }
        }
    }
}

data class MergeCacheEntry(
    val hash: String,
    val bpDir: File? = null,
    val rpDir: File? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val jsonCount: Int = 0,
    val addonName: String = ""
) : java.io.Serializable