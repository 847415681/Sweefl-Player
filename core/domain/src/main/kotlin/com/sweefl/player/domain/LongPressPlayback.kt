package com.sweefl.player.domain

const val DEFAULT_LONG_PRESS_SPEED = 2f
val LONG_PRESS_SPEED_CHOICES = listOf(1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

fun normalizeLongPressSpeed(value: Float): Float =
    if (!value.isFinite() || value <= 1f) DEFAULT_LONG_PRESS_SPEED else value.coerceIn(1.25f, 3f)

/** The first recognizer to claim a press owns it until the next down. */
class PressGestureClaim {
    private enum class Owner { WAITING, DRAG, HOLD, FINISHED }
    private var owner = Owner.FINISHED
    fun begin() { owner = Owner.WAITING }
    fun claimDrag(): Boolean {
        if (owner != Owner.WAITING) return false
        owner = Owner.DRAG
        return true
    }
    fun claimHold(playing: Boolean, locked: Boolean): Boolean {
        if (!playing || locked || owner != Owner.WAITING) return false
        owner = Owner.HOLD
        return true
    }
    fun finishHold() { if (owner == Owner.HOLD) owner = Owner.FINISHED }
}

/** A hold captures the actual original speed once; every termination path uses the same end. */
class TemporaryPlaybackSpeed {
    private var original: Float? = null
    val active: Boolean get() = original != null
    fun begin(current: Float, requested: Float, playing: Boolean, locked: Boolean): Float? {
        if (active || !playing || locked || !current.isFinite() || current <= 0f) return null
        original = current
        return normalizeLongPressSpeed(requested)
    }
    fun end(): Float? = original.also { original = null }
}
