package com.hearassist.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 每次下载独占临时文件，取消的旧任务只能清理自身文件。 */
internal class PendingApk(directory: File, version: Int) : AutoCloseable {
    val file: File = File.createTempFile("update-$version-", ".apk.part", directory)

    fun publish(destination: File): File {
        Files.move(file.toPath(), destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return destination
    }

    override fun close() {
        file.delete()
    }
}
