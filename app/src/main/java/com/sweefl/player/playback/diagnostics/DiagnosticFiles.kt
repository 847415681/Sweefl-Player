package com.sweefl.player.playback.diagnostics

import java.io.File

internal fun atomicWrite(file: File, text: String) {
    val atomic = android.util.AtomicFile(file)
    val stream = atomic.startWrite()
    try { stream.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
    catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
}

