@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sweefl.player.playback.preview

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Ephemeral byte cache owned by ONE immutable preview source/host, never the main player.
 * Construction and close do no disk IO. Media3 owns range/offset/length/redirect handling;
 * we only choose cache eligibility and partition representations by URI and request headers. */
internal class PreviewNetworkCache(context: Context, private val source: PreviewSource) {
    private val appContext = context.applicationContext
    private val directoryName = "seek-preview-${UUID.randomUUID()}"
    private val lock = Any()
    private val closed = AtomicBoolean()
    private var disabled = false
    private var directory: File? = null
    private var cache: SimpleCache? = null
    private var readers = 0

    fun factory(upstream: DataSource.Factory): DataSource.Factory =
        DataSource.Factory { PreviewDataSource(upstream) }

    /** The caller has already released its engine. A timed-out engine release may still have
     * loader work: those leases defer disposal, without blocking the caller/UI. */
    fun close() {
        if (closed.compareAndSet(false, true)) disposeWhenUnused()
    }

    @Suppress("DEPRECATION") // Deliberately use the existing file index, not a shared database.
    private fun acquire(): SimpleCache? = synchronized(lock) {
        if (closed.get() || disabled) return@synchronized null
        try {
            if (cache == null) {
                // All hosts share this barrier. Sweep old process remnants before ANY host
                // creates a cache; do not wait for disk IO or disable caching permanently.
                if (!orphanCleanup.readyForCreation { appContext.cacheDir }) return@synchronized null
                val root = appContext.cacheDir.canonicalFile
                if (StatFs(root.path).availableBytes < MIN_FREE_BYTES) {
                    disabled = true
                    return@synchronized null
                }
                val owned = File(root, directoryName)
                // Never adopt or remove a pre-existing directory, even in a UUID collision.
                if (owned.canonicalFile.parentFile != root || !owned.mkdir()) {
                    disabled = true
                    return@synchronized null
                }
                directory = owned
                cache = SimpleCache(owned, LeastRecentlyUsedCacheEvictor(MAX_BYTES))
                cache!!.checkInitialization()
            }
            if (closed.get()) {
                disposeWhenUnused()
                return@synchronized null
            }
            readers++
            cache
        } catch (_: IOException) {
            disabled = true; disposeWhenUnused(); null
        } catch (_: RuntimeException) {
            disabled = true; disposeWhenUnused(); null
        }
    }

    private fun releaseLease() = synchronized(lock) {
        check(readers > 0)
        readers--
        if (readers == 0 && (closed.get() || disabled)) disposeWhenUnused()
    }

    private fun disable() = synchronized(lock) {
        disabled = true
        disposeWhenUnused()
    }

    private fun disposeWhenUnused() {
        cleanup.execute {
            synchronized(lock) {
                if (readers != 0 || (!closed.get() && !disabled)) return@synchronized
                val owned = directory ?: return@synchronized
                try {
                    cache?.release()
                    cache = null
                    // Both the parent and the exact UUID name are checked before deletion.
                    val root = appContext.cacheDir.canonicalFile
                    if (owned.name == directoryName && owned.parentFile == root && owned.canonicalFile == owned) {
                        orphanCleanup.deleteOwnedDirectory(root, owned)
                    }
                    directory = null
                } catch (_: IOException) {
                    // Leave only this app-cache directory for OS reclamation; never broaden cleanup.
                } catch (_: RuntimeException) {
                    // A later close/last lease may retry; no disk failure escapes onto the UI.
                }
            }
        }
    }

    private fun cacheKey(spec: DataSpec): String {
        val identity = buildString {
            fun part(value: String?) {
                if (value == null) append("-:") else append(value.length).append(':').append(value)
            }
            part(source.uri); part(source.userAgent)
            com.sweefl.player.playback.engine.playbackRequestHeaders(source.requestHeaders).toSortedMap()
                .forEach { (key, value) -> part(key); part(value) }
            part(spec.uri.toString()); part(spec.key)
            part((spec.flags and DataSpec.FLAG_ALLOW_GZIP).toString())
            spec.httpRequestHeaders.toSortedMap().forEach { (key, value) -> part(key); part(value) }
        }
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun eligible(spec: DataSpec): Boolean =
        (spec.uri.scheme.equals("http", true) || spec.uri.scheme.equals("https", true)) &&
            spec.httpMethod == DataSpec.HTTP_METHOD_GET && spec.httpBody == null &&
            spec.httpRequestHeaders.keys.none { it.equals("Range", true) || it.equals("If-Range", true) }

    private fun cacheFailure(error: IOException): Boolean =
        generateSequence<Throwable>(error) { it.cause }.any {
            it is Cache.CacheException || it is FileDataSource.FileDataSourceException
        }

    private inner class PreviewDataSource(private val upstream: DataSource.Factory) : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var delegate: DataSource? = null
        private var leased = false

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
            delegate?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            check(delegate == null)
            if (closed.get()) throw IOException("Preview host closed")
            val available = if (eligible(dataSpec)) acquire() else null
            leased = available != null
            try {
                if (closed.get()) throw IOException("Preview host closed")
                val actual = if (available == null) upstream.createDataSource() else {
                    CacheDataSource.Factory().setCache(available)
                        .setUpstreamDataSourceFactory(upstream)
                        .setCacheKeyFactory(::cacheKey)
                        .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(available)
                            .setFragmentSize(FRAGMENT_BYTES))
                        // Do not wait for another loader's hole span. On a cache fault the
                        // existing loader/recovery policy retries through the plain upstream.
                        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR).createDataSource()
                }
                delegate = actual
                listeners.forEach(actual::addTransferListener)
                val request = if (available == null) dataSpec else dataSpec.buildUpon()
                    .setFlags(dataSpec.flags or DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build()
                return actual.open(request)
            } catch (error: IOException) {
                if (leased && cacheFailure(error)) disable()
                closeAfterFailedOpen()
                throw error
            } catch (error: RuntimeException) {
                if (leased) disable()
                closeAfterFailedOpen()
                throw error
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            try {
                return checkNotNull(delegate).read(buffer, offset, length)
            } catch (error: IOException) {
                if (leased && cacheFailure(error)) disable()
                throw error
            }
        }

        override fun getUri(): Uri? = delegate?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

        override fun close() {
            val actual = delegate
            val hadLease = leased
            delegate = null; leased = false
            try {
                actual?.close()
            } catch (error: IOException) {
                if (hadLease && cacheFailure(error)) disable()
                throw error
            } finally {
                if (hadLease) releaseLease()
            }
        }

        private fun closeAfterFailedOpen() {
            try { close() } catch (_: IOException) { } catch (_: RuntimeException) { }
        }
    }

    companion object {
        // LRU bounds committed media bytes; in-flight files are fragmented at 2 MiB.
        private const val MAX_BYTES = 32L * 1024 * 1024
        private const val FRAGMENT_BYTES = 2L * 1024 * 1024
        private const val MIN_FREE_BYTES = 64L * 1024 * 1024
        private val cleanup = Executors.newSingleThreadExecutor { task ->
            Thread(task, "preview-cache-cleanup").apply { isDaemon = true }
        }
        private val orphanCleanup = PreviewCacheCleanup(cleanup)
    }
}
