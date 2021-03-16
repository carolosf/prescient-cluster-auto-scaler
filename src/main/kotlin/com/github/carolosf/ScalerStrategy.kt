package com.github.carolosf

import org.jboss.logging.Logger
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.max

class ScalerStrategy() : IScalerStrategy {
    companion object {
        private val LOG: Logger = Logger.getLogger(ScalerStrategy::class.java)
    }
    override fun calculateScaleFactor(currentScaleUpFactor: Int,
                                      totalAvailable: Resources,
                                      asgOneNodeCapacity: Resources,
                                      onlyAddNodes: Boolean
    ):Int {
        if (currentScaleUpFactor <= 0) {
            return 0
        }
        val desiredCpu = BigDecimal(currentScaleUpFactor).multiply(asgOneNodeCapacity.cpu)
        val scaleUpCpu = desiredCpu.minus(totalAvailable.cpu).divide(asgOneNodeCapacity.cpu, RoundingMode.HALF_DOWN)

        val desiredMemory = BigDecimal(currentScaleUpFactor).multiply(asgOneNodeCapacity.memory)
        val scaleUpMemory = desiredMemory.minus(totalAvailable.memory).divide(asgOneNodeCapacity.memory,
            RoundingMode.HALF_DOWN
        )

        val desiredPods = BigDecimal(currentScaleUpFactor).multiply(BigDecimal(asgOneNodeCapacity.pods))
        val scaleUpPods = desiredPods.minus(BigDecimal(totalAvailable.pods)).divide(
            BigDecimal(asgOneNodeCapacity.pods),
            RoundingMode.HALF_DOWN
        )

        val desiredNewNodes = max(
            max(
                scaleUpCpu.setScale(0, RoundingMode.UP).longValueExact(),
                scaleUpMemory.setScale(0, RoundingMode.UP).longValueExact()
            ), scaleUpPods.setScale(0, RoundingMode.UP).longValueExact()
        ).toInt()

        if (onlyAddNodes && desiredNewNodes < 0) {
            LOG.warn("Only add nodes never delete nodes enabled - running ${abs(desiredNewNodes)} more nodes than necessary")
            return 0
        }

        return desiredNewNodes
    }
}