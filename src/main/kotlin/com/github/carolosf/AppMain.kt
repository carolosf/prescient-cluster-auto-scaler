package com.github.carolosf

import io.fabric8.kubernetes.api.model.NodeStatus
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.quarkus.runtime.annotations.QuarkusMain
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.autoscaling.AutoScalingClient
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsRequest
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupRequest
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max

data class Resources(val cpu: BigDecimal, val memory: BigDecimal, val pods: Long)

interface IScalerStrategy {
    fun calculateScaleFactor(currentScaleUpFactor: Int,
                             totalAvailable: Resources,
                             asgOneNodeCapacity: Resources
    ): Int
}

class ScalerStrategy() : IScalerStrategy {
    override fun calculateScaleFactor(currentScaleUpFactor: Int,
                                      totalAvailable: Resources,
                                      asgOneNodeCapacity: Resources

    ):Int {
        val desiredCpu = BigDecimal(currentScaleUpFactor).multiply(asgOneNodeCapacity.cpu)
        val scaleUpCpu = desiredCpu.minus(totalAvailable.cpu).divide(asgOneNodeCapacity.cpu)

        val desiredMemory = BigDecimal(currentScaleUpFactor).multiply(asgOneNodeCapacity.memory)
        val scaleUpMemory = desiredMemory.minus(totalAvailable.memory).divide(asgOneNodeCapacity.memory)

        val desiredPods = BigDecimal(currentScaleUpFactor).multiply(BigDecimal(asgOneNodeCapacity.pods))
        val scaleUpPods = desiredPods.minus(BigDecimal(totalAvailable.pods)).divide(BigDecimal(asgOneNodeCapacity.pods))

        return max(max(
            scaleUpCpu.setScale(0, RoundingMode.UP).longValueExact(),
            scaleUpMemory.setScale(0, RoundingMode.UP).longValueExact()
        ), scaleUpPods.setScale(0, RoundingMode.UP).longValueExact()).toInt()
    }

}

// TODO: This is still proof of concept stage, don't use this in production without testing and tidy up
@QuarkusMain
class AppMain {
    companion object {
        private const val cpuMConstant = 1000 // TODO: this is how many m there are per core need to find the code for this and make sure

        @JvmStatic
        fun main(args: Array<String>) {
            val client = getAndConfigureKubernetesClient()
            client.use {
                val asgOneNodeCapacity = Resources(
                    BigDecimal(System.getenv("NODE_CPU")?.toLong() ?: 8000),
                    BigDecimal(System.getenv("NODE_MEMORY")?.toLong() ?: 16323915776),
                    System.getenv("NODE_PODS")?.toLong() ?: 110
                )
                val currentScaleUpFactor = System.getenv("SCALE_FACTOR")?.toInt() ?: 1

                val nodeNames = getSchedulableWorkerNodes(it)
                val activePodsByNode: Map<String, List<Pod>> = nodeNames.map { nodeName ->
                    nodeName to getNodeNonTerminatedPodList(it, nodeName)
                }.toMap()

                val nodeCapacity: MutableMap<String, Resources> = mutableMapOf()
                val nodeAllocatable: MutableMap<String, Resources> = mutableMapOf()
                val podRequested: MutableMap<String, Resources> = mutableMapOf()
                val podLimits: MutableMap<String, Resources> = mutableMapOf()

                nodeNames.forEach { node ->
                    val nodeStatus = it.nodes().withName(node)?.get()?.status
                    nodeCapacity[node] = Resources(
                        getCpuCapacityFromNodeStatus(nodeStatus),
                        getMemoryCapacityFromNodeStatus(nodeStatus),
                        getPodCapacityFromNodeStatus(nodeStatus)
                    )
                    nodeAllocatable[node] = Resources(
                        getAllocatableCpuFromNodeStatus(nodeStatus),
                        getAllocatableMemoryFromNodeStatus(nodeStatus),
                        getAllocatablePodsFromNodeStatus(nodeStatus)
                    )

                    val activePodsOnNode = activePodsByNode[node]
                    podRequested[node] = Resources(
                        getTotalCpuRequestsFromListOfPods(activePodsOnNode),
                        getTotalMemoryRequestsFromListOfPods(activePodsOnNode),
                        activePodsOnNode?.count()?.toLong() ?: 0
                    )
                    podLimits[node] = Resources(
                        getTotalCpuLimitsFromListOfPods(activePodsOnNode),
                        getTotalMemoryLimitsFromListOfPods(activePodsOnNode),
                        0
                    )
                }

                val totalCapacity = Resources(
                    nodeCapacity.values.fold(BigDecimal.ZERO, { a, r -> r.cpu + a }),
                    nodeCapacity.values.fold(BigDecimal.ZERO, { a, r -> r.memory + a }),
                    nodeCapacity.values.fold(0, { a, r -> r.pods + a })
                )

                val totalAllocatable = Resources(
                    nodeAllocatable.values.fold(BigDecimal.ZERO, { a, r -> r.cpu + a }),
                    nodeAllocatable.values.fold(BigDecimal.ZERO, { a, r -> r.memory + a }),
                    nodeAllocatable.values.fold(0, { a, r -> r.pods + a })
                )

                val totalRequested = Resources(
                    podRequested.values.fold(BigDecimal.ZERO, { a, r -> r.cpu + a }),
                    podRequested.values.fold(BigDecimal.ZERO, { a, r -> r.memory + a }),
                    podRequested.values.fold(0, { a, r -> r.pods + a })
                )

                val totalLimits = Resources(
                    podLimits.values.fold(BigDecimal.ZERO, { a, r -> r.cpu + a }),
                    podLimits.values.fold(BigDecimal.ZERO, { a, r -> r.memory + a }),
                    podLimits.values.fold(0, { a, r -> r.pods + a })
                )

                val totalAvailable = Resources(
                    totalAllocatable.cpu - totalRequested.cpu,
                    totalAllocatable.memory - totalRequested.memory,
                    totalAllocatable.pods - totalRequested.pods
                )

                val scalerStrategy = ScalerStrategy()
                val scaleUp =
                    scalerStrategy.calculateScaleFactor(currentScaleUpFactor, totalAvailable, asgOneNodeCapacity)

                if (scaleUp > 0) {
                    println("Scale by $scaleUp")
                    asgDesiredCapacity(scaleUp)
                }
            }
        }

        private fun getAllocatablePodsFromNodeStatus(nodeStatus: NodeStatus?) =
            nodeStatus?.capacity?.get("pods")?.amount?.toLong() ?: 0

        private fun getPodCapacityFromNodeStatus(nodeStatus: NodeStatus?) =
            nodeStatus?.capacity?.get("pods")?.amount?.toLong() ?: 0

        private fun getTotalMemoryLimitsFromListOfPods(activePodsOnNode: List<Pod>?) =
            activePodsOnNode?.fold(BigDecimal.ZERO, { a, p ->
                return@fold a +
                        p.spec.initContainers.fold(
                            BigDecimal.ZERO,
                            { aa, cc -> getMemAmount(cc.resources.limits?.get("memory")) + aa }) +
                        p.spec.containers.fold(
                            BigDecimal.ZERO,
                            { aa, cc -> getMemAmount(cc.resources.limits?.get("memory")) + aa }) + (getMemAmount(
                    p.spec.overhead?.get("memory")
                ))
            }
            ) ?: BigDecimal.ZERO

        private fun getTotalCpuLimitsFromListOfPods(activePodsOnNode: List<Pod>?) =
            activePodsOnNode?.fold(BigDecimal.ZERO, { a, p ->
                return@fold a +
                        p.spec.initContainers.fold(
                            BigDecimal.ZERO,
                            { aa, cc -> getCpuAmount(cc.resources.limits?.get("cpu")) + aa }) +
                        p.spec.containers.fold(
                            BigDecimal.ZERO,
                            { aa, cc -> getCpuAmount(cc.resources.limits?.get("cpu")) + aa }) + (getCpuAmount(
                    p.spec.overhead?.get("cpu")
                ))
            }
            ) ?: BigDecimal.ZERO

        private fun getTotalMemoryRequestsFromListOfPods(activePodsOnNode: List<Pod>?) =
            activePodsOnNode?.fold(BigDecimal.ZERO, { a, p ->
                return@fold a +
                        p.spec.initContainers.fold(
                            BigDecimal.ZERO,
                            { aa, cc -> getMemAmount(cc.resources.requests?.get("memory")) + aa }) +
                        p.spec.containers.fold(
                            BigDecimal.ZERO,
                            { aa, cc -> getMemAmount(cc.resources.requests?.get("memory")) + aa }) + (getMemAmount(
                    p.spec.overhead?.get("memory")
                ))
            }
            ) ?: BigDecimal.ZERO

        private fun getTotalCpuRequestsFromListOfPods(activePodsOnNode: List<Pod>?) =
            activePodsOnNode?.fold(BigDecimal.ZERO, { a, p ->
                return@fold a +
                        p.spec.initContainers.fold(
                            BigDecimal.ZERO,
                            { aa, cc -> getCpuAmount(cc.resources.requests?.get("cpu")) + aa }) +
                        p.spec.containers.fold(
                            BigDecimal.ZERO,
                            { aa, cc -> getCpuAmount(cc.resources.requests?.get("cpu")) + aa }) + (getCpuAmount(
                    p.spec.overhead?.get("cpu")
                ))
            }
            ) ?: BigDecimal.ZERO

        private fun getAllocatableMemoryFromNodeStatus(nodeStatus: NodeStatus?) =
            getMemAmount(nodeStatus?.allocatable?.get("memory"))

        private fun getAllocatableCpuFromNodeStatus(nodeStatus: NodeStatus?) =
            (nodeStatus?.allocatable?.get("cpu")?.amount?.toInt()?.let { BigDecimal(it) }
                ?: BigDecimal.ZERO) * BigDecimal(cpuMConstant)

        private fun getMemoryCapacityFromNodeStatus(nodeStatus: NodeStatus?) =
            getMemAmount(nodeStatus?.capacity?.get("memory"))

        private fun getCpuCapacityFromNodeStatus(nodeStatus: NodeStatus?) =
            (nodeStatus?.capacity?.get("cpu")?.amount?.toInt()?.let { BigDecimal(it) } ?: BigDecimal.ZERO) * BigDecimal(
                cpuMConstant
            )

        private fun getSchedulableWorkerNodes(it: DefaultKubernetesClient) =
            it.nodes().withLabelIn("node-role.kubernetes.io/worker", "true").list().items.filter { !it.spec.unschedulable } .map { node -> node.metadata.name }

        private fun getNodeNonTerminatedPodList(
            it: DefaultKubernetesClient,
            node: String?
        ) = it.pods()
            .withField("spec.nodeName", node)
            .withoutField("status.phase", "Succeeded")
            .withoutField("status.phase", "Failed").list().items

        private fun getAndConfigureKubernetesClient(): DefaultKubernetesClient {
            val config = ConfigBuilder().build()
            val client = DefaultKubernetesClient(config)
            return client
        }

        private fun getCpuAmount(q: Quantity?): BigDecimal {
            if (q == null) {
                return BigDecimal.ZERO
            }

            if (q.format != "m") {
                throw IllegalStateException("Unexpected format ${q.format}")
            }
            return BigDecimal(q.amount.toInt())
        }

        private fun getMemAmount(q: Quantity?): BigDecimal {
            if (q == null) {
                return BigDecimal.ZERO
            }

            return Quantity.getAmountInBytes(q)
        }

        private fun asgDesiredCapacity(scaleUp: Int) {
            val autoscaling = AutoScalingClient.builder()
                //.credentialsProvider(CREDENTIALS_PROVIDER_CHAIN)
                .region(Region.EU_WEST_2)
                .build()

            val autoScalingGroupName = ""

            val current = autoscaling.describeAutoScalingGroups(
                DescribeAutoScalingGroupsRequest.builder()
                    .autoScalingGroupNames(autoScalingGroupName).build()
            )

            val currentDesiredCapacity = current.autoScalingGroups().first().desiredCapacity()

            //   AwsCredentialsProviderChain.of(ProfileCredentialsProvider.builder()
            //                                                                 .profileName(TEST_CREDENTIALS_PROFILE_NAME)
            //                                                                 .build(),
            //                                       DefaultCredentialsProvider.create());

            val updateRequest: UpdateAutoScalingGroupRequest = UpdateAutoScalingGroupRequest.builder()
                .autoScalingGroupName(autoScalingGroupName).desiredCapacity(currentDesiredCapacity + scaleUp).build()
            autoscaling.updateAutoScalingGroup(updateRequest)

            // Check our updates
            val result = autoscaling.describeAutoScalingGroups(
                DescribeAutoScalingGroupsRequest.builder()
                    .autoScalingGroupNames(autoScalingGroupName).build()
            )
        }
    }
}