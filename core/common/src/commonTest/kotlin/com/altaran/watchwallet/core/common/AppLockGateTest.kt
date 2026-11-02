package com.altaran.watchwallet.core.common

import com.altaran.watchwallet.core.common.AppLockGate.Display
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLockGateTest {
    private fun started(appLock: Boolean) = AppLockGate().apply { onSettings(appLock) }

    @Test
    fun nothingIsShownBeforeTheSettingsAreKnown() {
        val gate = AppLockGate()
        assertEquals(Display.HIDDEN_UNTIL_SETTINGS_LOADED, gate.display)
        assertFalse(gate.needsAuthentication)
        gate.onForegrounded(1_000)
        assertEquals(Display.HIDDEN_UNTIL_SETTINGS_LOADED, gate.display)
    }

    @Test
    fun coldStartWithTheLockOnIsLocked() {
        val gate = started(appLock = true)
        assertEquals(Display.LOCKED, gate.display)
        assertTrue(gate.needsAuthentication)
    }

    @Test
    fun coldStartWithTheLockOffShowsTheContent() {
        assertEquals(Display.CONTENT, started(appLock = false).display)
    }

    @Test
    fun anAuthenticationBeforeTheSettingsArriveDoesNotChangeTheFirstDecision() {
        val gate = AppLockGate()
        gate.onForegrounded(5)
        gate.onSettings(true)
        assertEquals(Display.LOCKED, gate.display)
    }

    @Test
    fun onlyASuccessOpensTheLock() {
        val gate = started(appLock = true)
        // A failed or cancelled prompt calls nothing on the gate.
        gate.onForegrounded(1)
        gate.onSettings(true)
        assertEquals(Display.LOCKED, gate.display)
        gate.onAuthenticated()
        assertEquals(Display.CONTENT, gate.display)
    }

    @Test
    fun enablingTheLockInTheRunningAppDoesNotLockAtOnce() {
        val gate = started(appLock = false)
        gate.onSettings(true)
        assertEquals(Display.CONTENT, gate.display)
        // It locks the next time, after being away.
        gate.onBackgrounded(1_000)
        gate.onForegrounded(1_000 + AppLockGate.DEFAULT_RELOCK_AFTER_MS + 1)
        assertEquals(Display.LOCKED, gate.display)
    }

    @Test
    fun disablingTheLockOpensIt() {
        val gate = started(appLock = true)
        gate.onSettings(false)
        assertEquals(Display.CONTENT, gate.display)
    }

    @Test
    fun otherSettingChangesDoNotOpenTheLock() {
        val gate = started(appLock = true)
        gate.onSettings(true)
        assertEquals(Display.LOCKED, gate.display)
    }

    @Test
    fun otherSettingChangesDoNotCloseAnOpenLock() {
        val gate = started(appLock = true)
        gate.onAuthenticated()
        gate.onSettings(true)
        assertEquals(Display.CONTENT, gate.display)
    }

    @Test
    fun aShortAbsenceKeepsItOpenALongOneLocks() {
        val gate = started(appLock = true)
        gate.onAuthenticated()
        gate.onBackgrounded(1_000)
        gate.onForegrounded(1_000 + AppLockGate.DEFAULT_RELOCK_AFTER_MS)
        assertEquals(Display.CONTENT, gate.display)
        gate.onBackgrounded(50_000)
        gate.onForegrounded(50_000 + AppLockGate.DEFAULT_RELOCK_AFTER_MS + 1)
        assertEquals(Display.LOCKED, gate.display)
    }

    @Test
    fun aClockThatJumpsBackLocks() {
        val gate = started(appLock = true)
        gate.onAuthenticated()
        gate.onBackgrounded(1_000)
        gate.onForegrounded(10)
        assertEquals(Display.LOCKED, gate.display)
    }

    /** A new process (killed, or restored after process death) starts from the same state. */
    @Test
    fun aNewProcessIsAColdStart() {
        val before = started(appLock = true)
        before.onAuthenticated()
        assertEquals(Display.LOCKED, started(appLock = true).display)
    }

    /** Rotation, a widget tap or another app starting the screen: a new activity, the same gate. */
    @Test
    fun aNewScreenInTheSameProcessDoesNotSkipTheLock() {
        val gate = started(appLock = true)
        gate.onBackgrounded(1_000)
        gate.onForegrounded(1_000)
        assertEquals(Display.LOCKED, gate.display)
        // Once unlocked, rotating the phone does not ask again.
        gate.onAuthenticated()
        gate.onBackgrounded(2_000)
        gate.onForegrounded(2_001)
        assertEquals(Display.CONTENT, gate.display)
    }

    @Test
    fun theWindowIsSecureWhenEitherSettingAsksForIt() {
        assertTrue(AppLockGate.isWindowSecure(secureScreen = true, appLock = false))
        assertTrue(AppLockGate.isWindowSecure(secureScreen = false, appLock = true))
        assertFalse(AppLockGate.isWindowSecure(secureScreen = false, appLock = false))
    }
}
