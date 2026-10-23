package com.sweefl.player.playback.preview

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** One instance per process. No cache may be created until this one-shot sweep finishes.
 * A skipped/failed/budget-limited sweep is not retried while this process can own live caches. */
internal class PreviewCacheCleanup(private val executor: Executor) {
    private val started = AtomicBoolean()
    @Volatile private var finished = false

    /** Does no disk IO and never waits. Until ready, callers use the uncached upstream. */
    fun readyForCreation(cacheRoot: () -> File): Boolean {
        if (finished) return true
        if (started.compareAndSet(false, true)) {
            executor.execute {
                try {
                    sweep(cacheRoot().canonicalFile)
                } catch (_: IOException) {
                    // Best effort only; do not prevent preview playback on a disk failure.
                } catch (_: RuntimeException) {
                    // Includes inaccessible directories and directory iteration failures.
                } finally {
                    finished = true
                }
            }
        }
        return finished
    }

    private fun sweep(root: File) {
        val budget = Budget()
        val path = root.toPath()
        if (!safeDirectory(path)) return
        Files.newDirectoryStream(path).use { entries ->
            val iterator = entries.iterator()
            var directories = 0
            while (budget.take() && iterator.hasNext()) {
                val child = iterator.next()
                if (!ownedName.matches(child.fileName.toString())) continue
                if (++directories > MAX_DIRECTORIES) break
                deleteOwnedDirectory(root, child.toFile(), budget)
            }
        }
    }

    /** Also used after normal lease-aware release. Never follows (or removes) symbolic links. */
    fun deleteOwnedDirectory(root: File, owned: File) {
        deleteOwnedDirectory(root, owned, Budget())
    }

    private fun deleteOwnedDirectory(root: File, owned: File, budget: Budget) {
        try {
            if (!ownedName.matches(owned.name) || owned.parentFile != root ||
                root.canonicalFile != root || !safeDirectory(root.toPath()) ||
                !safeDirectory(owned.toPath())) return
            deleteTree(owned.toPath(), budget, 0)
        } catch (_: IOException) {
            // Leave any remainder for OS reclamation or a subsequent process startup.
        } catch (_: RuntimeException) {
            // A single inaccessible orphan must not make cache creation fail.
        }
    }

    private fun deleteTree(path: Path, budget: Budget, depth: Int) {
        if (!budget.take() || depth > MAX_DEPTH || !safePath(path)) return
        if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
            Files.newDirectoryStream(path).use { entries ->
                val iterator = entries.iterator()
                while (budget.available() && iterator.hasNext()) {
                    deleteTree(iterator.next(), budget, depth + 1)
                }
            }
        }
        // Recheck after iteration; a directory containing a skipped link is left intact.
        if (budget.available() && safePath(path)) Files.deleteIfExists(path)
    }

    private fun safeDirectory(path: Path): Boolean =
        safePath(path) && Files.isDirectory(path, NOFOLLOW_LINKS)

    private fun safePath(path: Path): Boolean =
        !Files.isSymbolicLink(path) && path.toFile().canonicalFile == path.toFile().absoluteFile

    private class Budget {
        private var remaining = MAX_ENTRIES
        private val startedAt = System.nanoTime()
        fun available(): Boolean = remaining > 0 && System.nanoTime() - startedAt < MAX_NANOS
        fun take(): Boolean {
            if (!available()) return false
            remaining--
            return true
        }
    }

    private companion object {
        // Exact lowercase UUID v4/variant format emitted by UUID.randomUUID(), not a prefix glob.
        val ownedName = Regex("seek-preview-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        const val MAX_DIRECTORIES = 64
        const val MAX_ENTRIES = 8192
        const val MAX_DEPTH = 16
        const val MAX_NANOS = 1_000_000_000L
    }
}
