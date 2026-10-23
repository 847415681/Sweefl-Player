package com.sweefl.player.playback.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executor

/** Real temporary filesystem tests; no Android classes or stub return defaults. */
class PreviewCacheCleanupTest {
    @get:Rule val temporary = TemporaryFolder()

    private class QueuedExecutor : Executor {
        val tasks = mutableListOf<Runnable>()
        override fun execute(command: Runnable) { tasks += command }
        fun runNext() { tasks.removeAt(0).run() }
    }

    private fun owned(root: File): File = File(root, "seek-preview-${UUID.randomUUID()}").apply {
        check(mkdir())
        File(this, "fragment").writeText("cached media")
    }

    @Test fun sweepIsAsynchronousAndRunsOnlyOnceBeforeCreation() {
        val root = temporary.newFolder("cache").canonicalFile
        val orphan = owned(root)
        val executor = QueuedExecutor()
        val cleanup = PreviewCacheCleanup(executor)
        var rootReads = 0
        val rootProvider = { rootReads++; root }
        assertFalse(cleanup.readyForCreation(rootProvider))
        assertFalse(cleanup.readyForCreation(rootProvider)) // another host, same process
        assertEquals(0, rootReads)
        assertEquals(1, executor.tasks.size)
        assertTrue(orphan.exists())
        executor.runNext()
        assertFalse(orphan.exists())
        assertTrue(cleanup.readyForCreation(rootProvider))
        val liveOtherHost = owned(root)
        repeat(3) { assertTrue(cleanup.readyForCreation(rootProvider)) }
        assertTrue(liveOtherHost.exists())
        assertEquals(1, rootReads)
        assertTrue(executor.tasks.isEmpty())
    }

    @Test fun sweepRequiresExactOwnedUuidDirectory() {
        val root = temporary.newFolder("cache").canonicalFile
        val preserved = listOf("seek-preview-user", "seek-preview-00000000-0000-0000-0000-000000000000",
            "seek-preview-12345678-1234-4234-8234-123456789abc-extra", "other-cache")
            .map { File(root, it).apply { mkdir(); File(this, "keep").writeText("keep") } }
        val regularFile = File(root, "seek-preview-${UUID.randomUUID()}").apply { writeText("keep") }
        val orphan = owned(root)
        val cleanup = PreviewCacheCleanup(Executor { it.run() })
        assertTrue(cleanup.readyForCreation { root })
        assertFalse(orphan.exists())
        preserved.forEach { assertTrue(File(it, "keep").exists()) }
        assertTrue(regularFile.exists())
    }

    @Test fun refusesOutsideRootAndWrongNamesDuringNormalDisposal() {
        val root = temporary.newFolder("cache").canonicalFile
        val outside = owned(temporary.newFolder("outside").canonicalFile)
        val unrelated = File(root, "unrelated").apply { mkdir(); File(this, "keep").writeText("keep") }
        val cleanup = PreviewCacheCleanup(Executor { it.run() })
        cleanup.deleteOwnedDirectory(root, outside)
        cleanup.deleteOwnedDirectory(root, unrelated)
        assertTrue(File(outside, "fragment").exists())
        assertTrue(File(unrelated, "keep").exists())
        val normal = owned(root)
        cleanup.deleteOwnedDirectory(root, normal)
        assertFalse(normal.exists())
    }

    @Test fun symbolicLinksNeverLeadToOutsideDeletion() {
        val root = temporary.newFolder("cache").canonicalFile
        val outside = temporary.newFolder("outside").canonicalFile
        val keep = File(outside, "keep").apply { writeText("keep") }
        val orphan = owned(root)
        val topLink = File(root, "seek-preview-${UUID.randomUUID()}")
        try {
            Files.createSymbolicLink(topLink.toPath(), outside.toPath())
            Files.createSymbolicLink(File(orphan, "link").toPath(), outside.toPath())
        } catch (error: UnsupportedOperationException) {
            assumeNoException(error)
        } catch (error: IOException) {
            assumeNoException(error) // Windows may lack symlink privileges.
        } catch (error: SecurityException) {
            assumeNoException(error)
        }
        assertTrue(PreviewCacheCleanup(Executor { it.run() }).readyForCreation { root })
        assertTrue(keep.exists())
        assertTrue(Files.isSymbolicLink(topLink.toPath()))
        assertTrue(Files.isSymbolicLink(File(orphan, "link").toPath()))
    }

    @Test fun limitsNumberOfOrphanDirectoriesPerProcess() {
        val root = temporary.newFolder("cache").canonicalFile
        val orphans = List(70) { owned(root) }
        assertTrue(PreviewCacheCleanup(Executor { it.run() }).readyForCreation { root })
        assertTrue(orphans.count { it.exists() } >= 6)
        assertTrue(orphans.any { !it.exists() })
    }

    @Test fun deeplyNestedTreeIsLeftWithoutUnboundedRecursion() {
        val root = temporary.newFolder("cache").canonicalFile
        val orphan = owned(root)
        var nested = orphan
        repeat(20) { nested = File(nested, "nested").apply { check(mkdir()) } }
        val deepFile = File(nested, "keep").apply { writeText("bounded") }
        assertTrue(PreviewCacheCleanup(Executor { it.run() }).readyForCreation { root })
        assertTrue(deepFile.exists())
    }

    @Test fun failedSweepDoesNotBlockOrRepeatForLiveHosts() {
        val executor = QueuedExecutor()
        val cleanup = PreviewCacheCleanup(executor)
        assertFalse(cleanup.readyForCreation { throw IOException("unavailable cache root") })
        executor.runNext()
        assertTrue(cleanup.readyForCreation { error("must not revisit root") })
        assertTrue(executor.tasks.isEmpty())
    }
}
