package com.github.carolosf

interface IScalerStrategy {
    fun calculateScaleFactor(currentScaleUpFactor: Int,
                             totalAvailable: Resources,
                             asgOneNodeCapacity: Resources,
                             onlyAddNodes: Boolean
    ): Int
}