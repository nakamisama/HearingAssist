package com.hearassist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PendingApkTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun canceledOldDownloadCannotDeleteRetryFile() {
        val old = PendingApk(folder.root, 100)
        val retry = PendingApk(folder.root, 100)
        try {
            old.file.writeText("old partial")
            retry.file.writeText("new partial")
            assertNotEquals(old.file, retry.file)
            old.close()
            assertFalse(old.file.exists())
            assertTrue(retry.file.exists())
            assertEquals("new partial", retry.file.readText())
        } finally {
            old.close()
            retry.close()
        }
    }

    @Test
    fun cleanupAfterPublishNeverDeletesTheCompletedApk() {
        val target = File(folder.root, "update-100.apk").apply { writeText("previous") }
        val download = PendingApk(folder.root, 100)
        download.file.writeText("verified replacement")
        assertEquals(target, download.publish(target))
        download.close()
        assertFalse(download.file.exists())
        assertEquals("verified replacement", target.readText())
    }

    @Test
    fun publishFailureLeavesPreviousDestinationIntact() {
        val target = folder.newFolder("not-an-apk").apply { File(this, "sentinel").writeText("keep") }
        val download = PendingApk(folder.root, 100)
        try {
            download.file.writeText("replacement")
            assertThrows(IOException::class.java) { download.publish(target) }
            assertEquals("keep", File(target, "sentinel").readText())
        } finally {
            download.close()
        }
    }
}
