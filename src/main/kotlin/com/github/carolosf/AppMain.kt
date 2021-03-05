package com.github.carolosf

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

data class Resources (val cpu: Int, val memory: BigDecimal)

// TODO: This is still proof of concept stage, don't use this in production without testing and tidy up
@QuarkusMain
class AppMain {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val config = ConfigBuilder().build()
            val client = DefaultKubernetesClient(config)
            client.use {
                // todo: Needs massive optimisation
                val nodeNames = it.nodes().list().items.map{ node -> node.metadata.name}
                val activePods: MutableMap<String, List<Pod>> = mutableMapOf()
                nodeNames.forEach { node ->
                    activePods[node] = it.pods()
                        .withField("spec.nodeName", node)
                        .withoutField("status.phase", "Succeeded")
                        .withoutField("status.phase", "Failed").list().items
                }

                val nodeCapacity: MutableMap<String, Resources> = mutableMapOf()
                val nodeAllocatable: MutableMap<String, Resources> = mutableMapOf()
                val podRequested: MutableMap<String, Resources> = mutableMapOf()
                val podLimits: MutableMap<String, Resources> = mutableMapOf()

                val mConstant = 1000 // TODO: this is how many m there are per core need to find the code for this and make sure
                nodeNames.forEach { node ->
                    val status = it.nodes().withName(node)?.get()?.status
                    nodeCapacity[node] =  Resources(
                        (status?.capacity?.get("cpu")?.amount?.toInt() ?: 0) * mConstant,
                            getMemAmount(status?.capacity?.get("memory")),
                            )
                    nodeAllocatable[node] =  Resources(
                        (status?.allocatable?.get("cpu")?.amount?.toInt() ?: 0) * mConstant,
                        getMemAmount(status?.allocatable?.get("memory")),
                    )
                    podRequested[node] = Resources(
                            // https://github.com/kubernetes/kubectl/blob/b3f4363d68d55b34384965cae7a93cef2aac8ffe/pkg/util/resource/resource.go#L34
                            activePods[node]?.fold(0, {
                                a, p ->
                                return@fold a +
                                p.spec.initContainers.fold(0, {aa, cc -> getCpuAmount(cc.resources.requests?.get("cpu")) + aa}) +
                                p.spec.containers.fold(0, {aa, cc -> getCpuAmount(cc.resources.requests?.get("cpu"))+ aa}) + (getCpuAmount(p.spec.overhead?.get("cpu")))
                            }
                            ) ?: 0,
                        activePods[node]?.fold(BigDecimal.ZERO, {
                                a, p ->
                            return@fold a +
                                    p.spec.initContainers.fold(BigDecimal.ZERO, {aa, cc -> getMemAmount(cc.resources.requests?.get("memory")) + aa}) +
                                    p.spec.containers.fold(BigDecimal.ZERO, {aa, cc -> getMemAmount(cc.resources.requests?.get("memory")) + aa}) + (getMemAmount(p.spec.overhead?.get("memory")))
                        }
                        ) ?: BigDecimal.ZERO
                    )
                    podLimits[node] = Resources(
                        // https://github.com/kubernetes/kubectl/blob/b3f4363d68d55b34384965cae7a93cef2aac8ffe/pkg/util/resource/resource.go#L34
                        activePods[node]?.fold(0, {
                                a, p ->
                            return@fold a +
                                    p.spec.initContainers.fold(0, {aa, cc -> getCpuAmount(cc.resources.limits?.get("cpu")) + aa}) +
                                    p.spec.containers.fold(0, {aa, cc -> getCpuAmount(cc.resources.limits?.get("cpu"))+ aa}) + (getCpuAmount(p.spec.overhead?.get("cpu")))
                        }
                        ) ?: 0,
                        activePods[node]?.fold(BigDecimal.ZERO, {
                                a, p ->
                            return@fold a +
                                    p.spec.initContainers.fold(BigDecimal.ZERO, {aa, cc -> getMemAmount(cc.resources.limits?.get("memory")) + aa}) +
                                    p.spec.containers.fold(BigDecimal.ZERO, {aa, cc -> getMemAmount(cc.resources.limits?.get("memory")) + aa}) + (getMemAmount(p.spec.overhead?.get("memory")))
                        }
                        ) ?: BigDecimal.ZERO
                    )
                }

                val totalCapacity = Resources(
                    nodeCapacity.values.fold(0, {a,r -> r.cpu + a }),
                    nodeCapacity.values.fold(BigDecimal.ZERO, { a, r -> r.memory + a })
                )

                val totalAllocatable = Resources(
                    nodeAllocatable.values.fold(0, {a,r -> r.cpu + a }),
                    nodeAllocatable.values.fold(BigDecimal.ZERO, { a, r -> r.memory + a })
                )

                val totalRequested = Resources(
                    podRequested.values.fold(0, {a,r -> r.cpu + a }),
                    podRequested.values.fold(BigDecimal.ZERO, { a, r -> r.memory + a })
                )

                val totalLimits = Resources(
                    podRequested.values.fold(0, {a,r -> r.cpu + a }),
                    podRequested.values.fold(BigDecimal.ZERO, { a, r -> r.memory + a })
                )

                val asgOneNodeCapacity = Resources(
                    8000,
                    BigDecimal(16323915776)
                )

                val totalAvailable = Resources(
                    totalAllocatable.cpu - totalRequested.cpu,
                    totalAllocatable.memory - totalRequested.memory
                )

                var scaleUp = 0
                if (totalAvailable.cpu < asgOneNodeCapacity.cpu) {
                    scaleUp = 1
                }

                if (totalAvailable.memory < asgOneNodeCapacity.memory) {
                    scaleUp = 1
                }

                if (scaleUp > 0) {
                    println("Scale by $scaleUp")
                    asgDesiredCapacity(scaleUp)
                }

                // capacity
                // allocatable
                // requested
                // limits
            }
        }

        private fun getCpuAmount(q : Quantity?): Int {
            if (q == null) {
                return 0
            }

            if (q.format != "m") {
                throw IllegalStateException("Unexpected format ${q.format}")
            }
            return q.amount.toInt()
        }

        private fun getMemAmount(q : Quantity?): BigDecimal {
            if (q == null) {
                return BigDecimal.ZERO
            }

            return Quantity.getAmountInBytes(q)
        }

        private fun asgDesiredCapacity(scaleUp: Int) {
            //   AwsCredentialsProviderChain.of(ProfileCredentialsProvider.builder()
            //                                                                 .profileName(TEST_CREDENTIALS_PROFILE_NAME)
            //                                                                 .build(),
            //                                       DefaultCredentialsProvider.create());
            val autoscaling = AutoScalingClient.builder()
                //.credentialsProvider(CREDENTIALS_PROVIDER_CHAIN)
                .region(Region.EU_WEST_2)
                .build()
            val autoScalingGroupName = ""
            val updateRequest: UpdateAutoScalingGroupRequest = UpdateAutoScalingGroupRequest.builder()
                .autoScalingGroupName(autoScalingGroupName).desiredCapacity(20).build()
            autoscaling.updateAutoScalingGroup(updateRequest)

            // Check our updates
            val result = autoscaling.describeAutoScalingGroups(
                DescribeAutoScalingGroupsRequest.builder()
                    .autoScalingGroupNames(autoScalingGroupName).build()
            )
        }
    }
}