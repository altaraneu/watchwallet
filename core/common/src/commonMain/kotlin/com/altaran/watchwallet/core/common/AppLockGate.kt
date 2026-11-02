package com.altaran.watchwallet.core.common

/**
 * Whether the app content may be shown. Lives as long as the process, not the screen: rotating the
 * phone does not lock, while a new process is a cold start and always does.
 *
 * Fails closed: nothing is shown until the settings are known, and only a successful authentication
 * opens the lock. The first settings value is what was stored, so it locks; a later change from off
 * to on is the user enabling the lock in the running app, who must not be locked out on the spot.
 */
class AppLockGate(private val relockAfterMs: Long = DEFAULT_RELOCK_AFTER_MS) {
    enum class Display { HIDDEN_UNTIL_SETTINGS_LOADED, LOCKED, CONTENT }

    private var settingsLoaded = false
    private var lockEnabled = false
    private var locked = true
    private var backgroundedAt: Long? = null

    val display: Display
        get() = when {
            !settingsLoaded -> Display.HIDDEN_UNTIL_SETTINGS_LOADED
            lockEnabled && locked -> Display.LOCKED
            else -> Display.CONTENT
        }

    /** The platform authentication should be offered. */
    val needsAuthentication: Boolean get() = display == Display.LOCKED

    fun onSettings(appLock: Boolean) {
        locked = when {
            !settingsLoaded -> appLock
            !appLock -> false
            else -> locked && lockEnabled
        }
        settingsLoaded = true
        lockEnabled = appLock
    }

    /** Call with a monotonic clock, not wall time: changing the date must not open the lock. */
    fun onBackgrounded(nowMs: Long) {
        backgroundedAt = nowMs
    }

    fun onForegrounded(nowMs: Long) {
        val since = backgroundedAt
        backgroundedAt = null
        if (since != null && lockEnabled && (nowMs - since > relockAfterMs || nowMs < since)) locked = true
    }

    /** Only after authentication succeeded. A failure or a cancel changes nothing. */
    fun onAuthenticated() {
        locked = false
    }

    companion object {
        const val DEFAULT_RELOCK_AFTER_MS = 30_000L

        /** Screenshots off is the user's choice, but a locked app must not show balances in the task switcher. */
        fun isWindowSecure(secureScreen: Boolean, appLock: Boolean): Boolean = secureScreen || appLock
    }
}
