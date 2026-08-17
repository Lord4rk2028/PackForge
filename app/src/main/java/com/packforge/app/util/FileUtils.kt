package com.packforge.app.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object FileUtils {
    fun fastCopy(src: File, dest: File) {
        dest.parentFile?.mkdirs()
        FileInputStream(src).channel.use { input ->
            FileOutputStream(dest).channel.use { output ->
                output.transferFrom(input, 0, Long.MAX_VALUE)
            }
        }
    }
}
