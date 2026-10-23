@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sweefl.player.playback.network

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore

/**
 * Ordered, validated Range transport, not an offline cache or playlist scheduler.
 * A Factory shares its acceleration budget across range workers and probes; essential
 * sequential track streams remain managed by Media3 outside that budget. Permits are released only AFTER the owning thread closes the HTTP source.
 * Cancellation never closes a DefaultHttpDataSource concurrently with its open/read: worker
 * interruption plus finite upstream timeouts allow its finally block to perform cleanup.
 *
 * Chunk memory is bounded PER OPEN SESSION to threads * 256KiB (at most 8MiB), not globally.
 * A global retained-buffer semaphore would deadlock ordered readers if later chunks acquired
 * all permits before the first chunk. Callers must bound simultaneous DataSources separately.
 * Transfer callbacks report real requests and may run on different worker threads.
 * open/read are single-consumer; close may be called concurrently to request cancellation.
 */
class ParallelHttpDataSource private constructor(
    private val upstream: HttpDataSource.Factory,
    private val threads: Int,
    private val budget: Semaphore
) : DataSource {
    class Factory(private val upstream: HttpDataSource.Factory, private val threads: Int) : DataSource.Factory {
        init { require(threads in 1..32) }
        private val budget = Semaphore(threads, true)
        override fun createDataSource(): DataSource = ParallelHttpDataSource(upstream, threads, budget)
    }
    private val listeners = CopyOnWriteArrayList<TransferListener>()
    @Volatile private var session: Session? = null
    private class Session(val spec: DataSpec) {
        val lock = Any()
        @Volatile var closed = false
        val chunks = ArrayDeque<Future<ByteArray>>()
        var executor: ExecutorService? = null
        var sequentialExecutor: ExecutorService? = null
        var sequentialTask: Future<*>? = null
        // Accessed only on sequentialExecutor, including cleanup.
        var sequentialSource: HttpDataSource? = null
        var sequentialPermit = false
        var uri: Uri? = null
        var headers: Map<String, List<String>> = emptyMap()
        var etag = ""
        var total = 0L
        var length = 0L
        var scheduled = 0L
        var delivered = 0L
        var current: ByteArray? = null
        var cursor = 0
    }
    override fun addTransferListener(transferListener: TransferListener) { listeners.addIfAbsent(transferListener) }

    override fun open(dataSpec: DataSpec): Long {
        close()
        val s = Session(dataSpec)
        session = s
        try {
            val encoding = dataSpec.httpRequestHeaders.entries.firstOrNull { it.key.equals("Accept-Encoding", true) }?.value
            if (threads == 1 || dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET || dataSpec.httpBody != null ||
                (dataSpec.length != C.LENGTH_UNSET.toLong() && dataSpec.length < MIN_PARALLEL) ||
                (encoding != null && !encoding.equals("identity", true)) ||
                dataSpec.httpRequestHeaders.keys.any { key -> CONDITIONAL_HEADERS.any { it.equals(key, true) } }) {
                return sequential(s)
            }
            // Probe at RESOURCE OFFSET ZERO. DefaultHttpDataSource otherwise skipFully's a
            // large seek prefix on a 200 response, only for fallback to skip it a second time.
            val probeSpec = dataSpec.buildUpon().setPosition(0).setLength(CHUNK.toLong()).build()
            var first: ByteArray? = null
            val capable = try {
                withSource(s) { probe ->
                    probe.open(rangeSpec(probeSpec, 0, CHUNK.toLong(), null))
                    checkOpen(s)
                    val headers = probe.responseHeaders
                    val range = parseRange(header(headers, "Content-Range"))
                    val tag = header(headers, "ETag")
                    val identity = header(headers, "Content-Encoding").let { it == null || it.equals("identity", true) }
                    val available = range?.let { it.total - dataSpec.position } ?: 0L
                    val length = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else dataSpec.length
                    val valid = probe.responseCode == 206 && identity && range != null && range.start == 0L &&
                        range.end == minOf(CHUNK.toLong(), range.total) - 1 &&
                        length in MIN_PARALLEL..available && strongTag(tag)
                    if (valid) {
                        s.uri = probe.uri ?: dataSpec.uri
                        s.headers = headers.mapValues { it.value.toList() }
                        s.etag = tag!!; s.total = range!!.total; s.length = length
                        if (dataSpec.position == 0L) first = readExactly(s, probe, CHUNK)
                    }
                    valid
                }
            } catch (failure: HttpDataSource.InvalidResponseCodeException) {
                // Range rejection is not an authentication failure. Preserve 401/403 and
                // other meaningful errors; only explicitly unsupported probes fall back.
                if (failure.responseCode in setOf(400, 416, 501)) false else throw failure
            }
            checkOpen(s)
            if (!capable) return sequential(s)
            synchronized(s.lock) {
                checkOpen(s)
                first?.let { s.chunks.add(CompletableFuture.completedFuture(it)); s.scheduled = it.size.toLong() }
                s.executor = Executors.newFixedThreadPool(threads) { Thread(it, "SweeflHttpRange").apply { isDaemon = true } }
                while (s.chunks.size < threads && s.scheduled < s.length) schedule(s)
            }
            return s.length
        } catch (failure: Exception) {
            shutdown(s)
            if (session === s) session = null
            throw asIo(failure)
        }
    }

    private fun newSource(): HttpDataSource = upstream.createDataSource().also { result ->
        listeners.forEach(result::addTransferListener)
    }
    /** Only this request's owner touches and closes its upstream, even if open returns late. */
    private fun <T> withSource(s: Session, action: (HttpDataSource) -> T): T {
        checkOpen(s)
        budget.acquire()
        var source: HttpDataSource? = null
        try {
            checkOpen(s)
            source = newSource()
            return action(source)
        } finally {
            try { source?.close() } finally { budget.release() }
        }
    }

    private fun sequential(s: Session): Long {
        synchronized(s.lock) {
            checkOpen(s)
            s.sequentialExecutor = Executors.newSingleThreadExecutor { Thread(it, "SweeflHttpSequential").apply { isDaemon = true } }
        }
        return sequentialCall(s) {
            // Long-lived sequential audio/video streams must not retain the range-worker
            // budget: with a user limit of one that can starve the other selected track.
            // The setting limits parallel acceleration, not Media3's essential track loaders.
            checkOpen(s)
            val source = newSource()
            s.sequentialSource = source // Registered BEFORE open, including failed opens.
            val length = source.open(s.spec)
            checkOpen(s)
            s.uri = source.uri; s.headers = source.responseHeaders.mapValues { it.value.toList() }
            length
        }
    }
    private fun <T> sequentialCall(s: Session, action: () -> T): T {
        val future = synchronized(s.lock) {
            checkOpen(s)
            s.sequentialExecutor!!.submit(Callable {
                try {
                    checkOpen(s)
                    action().also { checkOpen(s) }
                } catch (failure: Exception) {
                    closeSequential(s)
                    throw failure
                } finally {
                    if (s.closed) closeSequential(s)
                }
            }).also { s.sequentialTask = it }
        }
        return future.get()
    }
    /** Runs exclusively on the single sequential owner executor. Idempotent, never races I/O. */
    private fun closeSequential(s: Session) {
        val source = s.sequentialSource
        s.sequentialSource = null
        try { source?.close() } finally {
            if (s.sequentialPermit) { s.sequentialPermit = false; budget.release() }
        }
    }

    /** Caller holds lock. A worker never retains a request permit while waiting on a future. */
    private fun schedule(s: Session) {
        if (s.closed || s.scheduled >= s.length) return
        val offset = s.scheduled
        val count = minOf(CHUNK.toLong(), s.length - offset).toInt()
        s.scheduled += count
        s.chunks.add(s.executor!!.submit(Callable {
            withSource(s) { source ->
                source.open(rangeSpec(s.spec, offset, count.toLong(), s.etag))
                checkOpen(s)
                val headers = source.responseHeaders
                val range = parseRange(header(headers, "Content-Range"))
                if (source.responseCode != 206 || range == null || range.start != s.spec.position + offset ||
                    range.end != safeEnd(s.spec.position + offset, count.toLong()) || range.total != s.total ||
                    header(headers, "ETag") != s.etag || (source.uri ?: s.spec.uri) != s.uri ||
                    header(headers, "Content-Encoding").let { it != null && !it.equals("identity", true) }) {
                    throw IOException("Range response changed resource, encoding or byte boundaries")
                }
                readExactly(s, source, count)
            }
        }))
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        if (length == 0) return 0
        val s = session ?: throw IOException("DataSource is not open")
        try {
            checkOpen(s)
            if (s.sequentialExecutor != null) {
                // Private transfer buffer: cancellation may release the caller while a socket
                // read is still blocked; the worker must never subsequently mutate caller memory.
                val bytes = ByteArray(minOf(length, CHUNK))
                val count = sequentialCall(s) { s.sequentialSource!!.read(bytes, 0, bytes.size) }
                checkOpen(s)
                if (count > 0) bytes.copyInto(buffer, offset, 0, count)
                return count
            }
            if (s.delivered == s.length) return C.RESULT_END_OF_INPUT
            if (s.current == null) {
                val future = synchronized(s.lock) {
                    checkOpen(s)
                    while (s.chunks.size < threads && s.scheduled < s.length) schedule(s)
                    s.chunks.peekFirst()
                } ?: throw IOException("Missing ordered Range chunk")
                s.current = future.get(); checkOpen(s); s.cursor = 0
            }
            val bytes = s.current!!
            val count = minOf(length, bytes.size - s.cursor)
            bytes.copyInto(buffer, offset, s.cursor, s.cursor + count)
            s.cursor += count; s.delivered += count
            if (s.cursor == bytes.size) {
                s.current = null
                synchronized(s.lock) { checkOpen(s); s.chunks.removeFirst() }
            }
            return count
        } catch (failure: Exception) { shutdown(s); throw asIo(failure) }
    }
    override fun getUri(): Uri? = session?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = session?.headers ?: emptyMap()
    override fun close() {
        val old = session; session = null
        if (old != null) shutdown(old)
    }
    private fun shutdown(s: Session) {
        synchronized(s.lock) {
            if (s.closed) return
            s.closed = true
            s.chunks.forEach { it.cancel(true) }; s.chunks.clear()
            s.executor?.shutdownNow(); s.executor = null
            s.sequentialTask?.cancel(true)
            // DO NOT shutdownNow this queue: cleanup must execute after a late open/read.
            s.sequentialExecutor?.let { executor ->
                executor.execute { try { closeSequential(s) } catch (_: Exception) { /* cleanup */ } }
                executor.shutdown()
            }
        }
    }
    private fun readExactly(s: Session, source: HttpDataSource, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var read = 0
        while (read < count) {
            checkOpen(s)
            val n = source.read(bytes, read, count - read)
            if (n == C.RESULT_END_OF_INPUT) throw EOFException("Truncated HTTP Range: $read/$count")
            if (n == 0) throw IOException("HTTP Range made no read progress")
            read += n
        }
        checkOpen(s)
        return bytes
    }
    private fun rangeSpec(spec: DataSpec, offset: Long, count: Long, etag: String?): DataSpec {
        val headers = spec.httpRequestHeaders.filterKeys {
            !it.equals("Accept-Encoding", true) && (etag == null ||
                (!it.equals("If-Match", true) && !it.equals("If-Range", true)))
        }.toMutableMap()
        headers["Accept-Encoding"] = "identity"
        if (etag != null) { headers["If-Match"] = etag; headers["If-Range"] = etag }
        return spec.subrange(offset, count).buildUpon().setHttpRequestHeaders(headers)
            .setFlags(spec.flags and DataSpec.FLAG_ALLOW_GZIP.inv()).build()
    }
    private fun checkOpen(s: Session) {
        if (s.closed || Thread.currentThread().isInterrupted) throw IOException("HTTP Range request cancelled")
    }
    private fun asIo(failure: Exception): IOException {
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        val cause = if (failure is ExecutionException) failure.cause ?: failure else failure
        return cause as? IOException ?: IOException("Parallel HTTP Range failed", cause)
    }
    private data class Range(val start: Long, val end: Long, val total: Long)
    private fun parseRange(value: String?): Range? {
        val match = value?.let { CONTENT_RANGE.matchEntire(it.trim()) } ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        return if (start <= end && end < total) Range(start, end, total) else null
    }
    private fun safeEnd(start: Long, count: Long): Long {
        if (start > Long.MAX_VALUE - (count - 1)) throw IOException("HTTP Range overflow")
        return start + count - 1
    }
    private fun header(headers: Map<String, List<String>>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.singleOrNull()
    private fun strongTag(tag: String?): Boolean = tag != null && tag.length >= 2 &&
        tag.startsWith('"') && tag.endsWith('"') && !tag.contains('\r') && !tag.contains('\n')
    private companion object {
        const val CHUNK = 256 * 1024
        const val MIN_PARALLEL = 1024L * 1024L
        val CONDITIONAL_HEADERS = setOf("Range", "If-Match", "If-Range", "If-None-Match", "If-Modified-Since", "If-Unmodified-Since")
        val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)", RegexOption.IGNORE_CASE)
    }
}
