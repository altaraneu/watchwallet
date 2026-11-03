package com.altaran.watchwallet.feature.portfolio

import org.junit.Assert.assertEquals
import org.junit.Test

class DonutValuesTest {
    @Test
    fun discreetModeHidesTheProportions() {
        assertEquals(listOf(1.0, 1.0, 1.0), donutValues(listOf(90.0, 9.0, 1.0), discreet = true))
    }

    @Test
    fun otherwiseTheValuesStay() {
        assertEquals(listOf(90.0, 9.0, 1.0), donutValues(listOf(90.0, 9.0, 1.0), discreet = false))
    }
}
