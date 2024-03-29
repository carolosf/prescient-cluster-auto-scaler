package com.github.carolosf

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class AppMainTest {
    @Test
    fun `ensure compute cpu scale factor with no available resources`() {
        val scaleFactor = ScalerStrategy().calculateScaleFactor(
            2,
            Resources(BigDecimal(0), BigDecimal(0), 0),
            Resources(BigDecimal(4000), BigDecimal(1000), 100),
            false
        )
        Assertions.assertEquals(2, scaleFactor)
    }

    @Test
    fun `ensure compute cpu scale factor with some cpu resources`() {
        val scaleFactor = ScalerStrategy().calculateScaleFactor(
            2,
            Resources(BigDecimal(2000), BigDecimal(990000), 990000),
            Resources(BigDecimal(4000), BigDecimal(1000), 100),
            false
        )
        Assertions.assertEquals(1, scaleFactor)
    }

    @Test
    fun `ensure compute cpu scale factor with one node cpu resources`() {
        val scaleFactor = ScalerStrategy().calculateScaleFactor(
            2,
            Resources(BigDecimal(4000), BigDecimal(990000),990000),
            Resources(BigDecimal(4000), BigDecimal(1000), 100),
            false
        )
        Assertions.assertEquals(1, scaleFactor)
    }

    @Test
    fun `ensure compute cpu scale factor of eight with one node cpu resources`() {
        val scaleFactor = ScalerStrategy().calculateScaleFactor(
            8,
            Resources(BigDecimal(4000), BigDecimal(990000), 990000),
            Resources(BigDecimal(4000), BigDecimal(1000), 100),
            false
        )
        Assertions.assertEquals(7, scaleFactor)
    }

    @Test
    fun `ensure compute memory scale factor with some memory resource`() {
        val scaleFactor = ScalerStrategy().calculateScaleFactor(
            2,
            Resources(BigDecimal(990000), BigDecimal(2000), 990000),
            Resources(BigDecimal(1000), BigDecimal(4000), 100),
            false
        )
        Assertions.assertEquals(1, scaleFactor)
    }

    @Test
    fun `ensure compute memory scale factor with one node memory resource`() {
        val scaleFactor = ScalerStrategy().calculateScaleFactor(
            2,
            Resources(BigDecimal(990000), BigDecimal(4000), 990000),
            Resources(BigDecimal(1000), BigDecimal(4000), 100),
            false
        )
        Assertions.assertEquals(1, scaleFactor)
    }

    @Test
    fun `ensure compute memory scale factor of 8 with one node memory resource`() {
        val scaleFactor = ScalerStrategy().calculateScaleFactor(
            8,
            Resources(BigDecimal(990000), BigDecimal(4000), 999999),
            Resources(BigDecimal(1000), BigDecimal(4000), 100),
            false
        )
        Assertions.assertEquals(7, scaleFactor)
    }

    @Test
    fun `ensure when too many resources delete nodes`() {
        val scaleFactor = ScalerStrategy().calculateScaleFactor(
            10,
            Resources(BigDecimal(1000), BigDecimal(1000), 1000),
            Resources(BigDecimal(1), BigDecimal(1), 1),
            false
        )
        Assertions.assertEquals(-990, scaleFactor)
    }

    @Test
    fun `ensure when too many resources only add nodes adds 0`() {
        val scaleFactor = ScalerStrategy().calculateScaleFactor(
            10,
            Resources(BigDecimal(1000), BigDecimal(1000), 1000),
            Resources(BigDecimal(1), BigDecimal(1), 1),
            true
        )
        Assertions.assertEquals(0, scaleFactor)
    }

    @Test
    fun `ensure scale factor of 0 returns correct result`() {
        val scaleFactor = ScalerStrategy().calculateScaleFactor(
            0,
            Resources(BigDecimal(990000), BigDecimal(4000), 999999),
            Resources(BigDecimal(1000), BigDecimal(4000), 100),
            false
        )
        Assertions.assertEquals(0, scaleFactor)
    }

    @Test
    fun `ensure scale factor of negative returns correct result`() {
        val scaleFactor = ScalerStrategy().calculateScaleFactor(
            -1,
            Resources(BigDecimal(990000), BigDecimal(4000), 999999),
            Resources(BigDecimal(1000), BigDecimal(4000), 100),
            false
        )
        Assertions.assertEquals(0, scaleFactor)
    }
}