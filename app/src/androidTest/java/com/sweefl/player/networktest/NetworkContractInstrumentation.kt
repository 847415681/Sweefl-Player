@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.sweefl.player.networktest

import android.app.Instrumentation
import android.app.Activity
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.datasource.*
import com.sweefl.player.playback.network.ParallelHttpDataSource
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList

/** No test framework dependency: actual Android runtime and actual production DataSource. */
class NetworkContractInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val passed = mutableListOf<String>()
        try {
            for (mode in listOf("range", "ignore", "reject", "weak")) {
                for ((offset, length) in listOf(0L to C.LENGTH_UNSET.toLong(), 345678L to 1250000L)) {
                    val fixture = Fixture(mode)
                    val data = ParallelHttpDataSource.Factory(fixture, 4).createDataSource()
                    val spec = DataSpec.Builder().setUri("https://fixture.invalid/media").setPosition(offset).setLength(length).build()
                    val out = ByteArrayOutputStream()
                    try {
                        data.open(spec)
                        val buffer = ByteArray(8191)
                        while (true) { val n = data.read(buffer, 0, buffer.size); if (n == C.RESULT_END_OF_INPUT) break; check(n > 0); out.write(buffer, 0, n) }
                    } finally { data.close() }
                    val expected = fixture.bytes.copyOfRange(offset.toInt(), if (length < 0) fixture.bytes.size else (offset + length).toInt())
                    check(sha(out.toByteArray()) == sha(expected)) { "$mode offset=$offset length=$length byte hash mismatch" }
                    fixture.awaitClosed()
                    check(fixture.sources.all { it.closeCount.get() == 1 && !it.wrongThread }) { "$mode close ownership" }
                    if (mode == "ignore" && offset > 0) check(fixture.specs.first().position == 0L) { "Probe must not skip seek prefix" }
                    if (mode == "range") check(fixture.maxActive.get() <= 4)
                    passed += "$mode offset=$offset length=$length SHA256=${sha(expected)}"
                }
            }
            for ((offset, length) in listOf(0L to C.LENGTH_UNSET.toLong(), 345678L to 1250000L)) {
                val fixture = Fixture("range")
                val data = ParallelHttpDataSource.Factory(fixture, 32).createDataSource()
                val actual = readAll(data, offset, length)
                val expected = fixture.bytes.copyOfRange(offset.toInt(), if (length < 0) fixture.bytes.size else (offset + length).toInt())
                check(sha(actual) == sha(expected)) { "32-worker byte hash mismatch" }
                assertClosed(fixture)
                check(fixture.maxActive.get() <= 32)
                passed += "range threads=32 offset=$offset length=$length SHA256=${sha(expected)}"
            }
            for (mode in listOf("malformed", "changed-etag", "truncated")) {
                val fixture = Fixture(mode)
                val data = ParallelHttpDataSource.Factory(fixture, 4).createDataSource()
                val out = ByteArrayOutputStream()
                var rejected = false
                try {
                    data.open(DataSpec.Builder().setUri("https://fixture.invalid/media").build())
                    val buffer = ByteArray(8191)
                    while (true) {
                        val n = data.read(buffer, 0, buffer.size)
                        if (n == C.RESULT_END_OF_INPUT) break
                        check(n > 0)
                        out.write(buffer, 0, n)
                    }
                } catch (_: java.io.IOException) { rejected = true }
                finally { data.close() }
                check(rejected) { "$mode was accepted" }
                // The valid probe may escape, but not one byte of a rejected worker chunk.
                check(out.size() <= 262144) { "$mode exposed unvalidated bytes" }
                check(out.toByteArray().contentEquals(fixture.bytes.copyOf(out.size())))
                assertClosed(fixture)
                passed += "$mode rejects worker response without exposing its bytes"
            }
            sharedFactory32(passed)
            for (threads in listOf(1, 4, 32)) {
                // Simulates a socket open which ignores interruption until explicitly released.
                val fixture = Fixture("block")
                val data = ParallelHttpDataSource.Factory(fixture, threads).createDataSource()
                val ended = CountDownLatch(1)
                val opener = Thread {
                    try { data.open(DataSpec.Builder().setUri("https://fixture.invalid/blocked").build()) }
                    catch (_: Exception) { }
                    finally { ended.countDown() }
                }
                opener.start(); check(fixture.entered.await(5, TimeUnit.SECONDS))
                data.close()
                check(fixture.sources.all { it.closeCount.get() == 0 }) { "Cross-thread close during open" }
                fixture.release.countDown()
                check(ended.await(5, TimeUnit.SECONDS)); fixture.awaitClosed()
                check(fixture.sources.all { it.closeCount.get() == 1 && !it.wrongThread }) { "Late open leaked or double closed" }
                passed += "cancel-during-open threads=$threads exactly-once owner close"
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\nPASS ${passed.size} transport contracts\n" + passed.joinToString("\n")); putString("result", "PASS") })
        } catch (failure: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "\nFAIL after ${passed.size}\n${failure.stackTraceToString()}"); putString("result", "FAIL") })
        }
    }
    private fun readAll(data: DataSource, offset: Long = 0, length: Long = C.LENGTH_UNSET.toLong()): ByteArray {
        val out = ByteArrayOutputStream()
        try {
            data.open(DataSpec.Builder().setUri("https://fixture.invalid/media").setPosition(offset).setLength(length).build())
            val buffer = ByteArray(8191)
            while (true) {
                val n = data.read(buffer, 0, buffer.size)
                if (n == C.RESULT_END_OF_INPUT) break
                check(n > 0)
                out.write(buffer, 0, n)
            }
        } finally { data.close() }
        return out.toByteArray()
    }
    private fun assertClosed(fixture: Fixture) {
        fixture.awaitClosed()
        check(fixture.sources.all { it.closeCount.get() == 1 && !it.wrongThread }) { "Leaked, duplicate, or cross-thread close" }
    }
    private fun sharedFactory32(passed: MutableList<String>) {
        val fixture = Fixture("slow", 10 * 1024 * 1024 + 137)
        val factory = ParallelHttpDataSource.Factory(fixture, 32)
        val sources = List(2) { factory.createDataSource() }
        val errors = CopyOnWriteArrayList<Throwable>()
        val hashes = CopyOnWriteArrayList<String>()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val readers = sources.map { source ->
            Thread {
                try { start.await(); hashes.add(sha(readAll(source))) }
                catch (failure: Throwable) { errors.add(failure) }
                finally { done.countDown() }
            }.apply { isDaemon = true; start() }
        }
        try {
            start.countDown()
            // Both probe responses have completed; workers remain held in read until the
            // global budget is saturated. A per-source budget would allow up to 62 workers.
            check(fixture.workersEntered.await(10, TimeUnit.SECONDS)) { "Shared worker budget did not saturate" }
            check(fixture.maxActive.get() == 32) { "Expected saturation at 32, got ${fixture.maxActive.get()}" }
        } finally {
            fixture.release.countDown()
            if (!done.await(20, TimeUnit.SECONDS)) {
                sources.forEach { it.close() }
                readers.forEach { it.interrupt() }
                check(done.await(5, TimeUnit.SECONDS)) { "Concurrent readers did not terminate" }
            }
            sources.forEach { it.close() }
        }
        check(errors.isEmpty()) { errors.joinToString("\n") { it.stackTraceToString() } }
        check(hashes.size == 2 && hashes.all { it == sha(fixture.bytes) }) { "Shared factory byte hashes differ" }
        check(fixture.maxActive.get() == 32) { "Shared factory exceeded 32: ${fixture.maxActive.get()}" }
        assertClosed(fixture)
        passed += "two simultaneous sources shared32 saturated cap=32 SHA256=${sha(fixture.bytes)} no leaks"
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

private class Fixture(val mode: String, size: Int = 2 * 1024 * 1024 + 137) : HttpDataSource.Factory {
    val bytes = ByteArray(size) { ((it * 31 + it / 251) and 255).toByte() }
    val sources = CopyOnWriteArrayList<FakeHttp>()
    val specs = CopyOnWriteArrayList<DataSpec>()
    val entered = CountDownLatch(1); val release = CountDownLatch(1)
    val workersEntered = CountDownLatch(32)
    val active = AtomicInteger(); val maxActive = AtomicInteger()
    override fun createDataSource(): HttpDataSource = FakeHttp(this).also { sources.add(it) }
    override fun setDefaultRequestProperties(defaultRequestProperties: MutableMap<String, String>): HttpDataSource.Factory = this
    fun awaitClosed() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (active.get() != 0 && System.nanoTime() < deadline) Thread.sleep(5)
        check(active.get() == 0) { "Leaked active sources=${active.get()}" }
    }
}
private class FakeHttp(val f: Fixture) : HttpDataSource {
    var owner: Thread? = null; var wrongThread = false
    val closeCount = AtomicInteger(); var counted = false
    var spec: DataSpec? = null; var cursor = 0; var end = 0; var code = 200
    var headers: Map<String, List<String>> = emptyMap()
    var worker = false
    var waited = false
    override fun open(dataSpec: DataSpec): Long {
        owner = Thread.currentThread(); spec = dataSpec; f.specs.add(dataSpec)
        counted = true; val active = f.active.incrementAndGet(); f.maxActive.accumulateAndGet(active, ::maxOf)
        if (f.mode == "block") {
            f.entered.countDown()
            var done = false
            while (!done) try { done = f.release.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
        }
        val bounded = dataSpec.length != C.LENGTH_UNSET.toLong()
        if (f.mode == "reject" && bounded && dataSpec.position == 0L && dataSpec.length == 262144L)
            throw HttpDataSource.InvalidResponseCodeException(400, "Range unsupported", null, emptyMap(), dataSpec, byteArrayOf())
        cursor = dataSpec.position.toInt()
        end = if (bounded) minOf(f.bytes.size, cursor + dataSpec.length.toInt()) else f.bytes.size
        code = if (f.mode == "ignore") 200 else 206
        headers = mapOf("Content-Range" to listOf("bytes $cursor-${end - 1}/${f.bytes.size}"), "ETag" to listOf(if (f.mode == "weak") "W/\"fixture\"" else "\"fixture\""))
        worker = dataSpec.httpRequestHeaders.keys.any { it.equals("If-Match", true) }
        if (worker && f.mode == "malformed") headers = headers + ("Content-Range" to listOf("bytes ${cursor + 1}-${end - 1}/${f.bytes.size}"))
        if (worker && f.mode == "changed-etag") headers = headers + ("ETag" to listOf("\"changed\""))
        val declaredLength = (end - cursor).toLong()
        if (worker && f.mode == "truncated") end-- // Header claims original size; payload ends early.
        return declaredLength
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkThread()
        if (worker && f.mode == "slow" && !waited) {
            waited = true
            f.workersEntered.countDown()
            check(f.release.await(15, TimeUnit.SECONDS)) { "Slow-worker gate timed out" }
        }
        if (cursor >= end) return C.RESULT_END_OF_INPUT
        val n = minOf(length, end - cursor); f.bytes.copyInto(buffer, offset, cursor, cursor + n); cursor += n; return n
    }
    override fun close() { checkThread(); closeCount.incrementAndGet(); if (counted) { counted = false; f.active.decrementAndGet() } }
    private fun checkThread() { if (owner != null && owner !== Thread.currentThread()) wrongThread = true }
    override fun getUri(): Uri? = spec?.uri
    override fun getResponseCode() = code
    override fun getResponseHeaders() = headers
    override fun addTransferListener(transferListener: TransferListener) { }
    override fun setRequestProperty(name: String, value: String) { }
    override fun clearRequestProperty(name: String) { }
    override fun clearAllRequestProperties() { }
}
