package com.github.carolosf

import io.fabric8.kubernetes.api.model.Namespace
import io.fabric8.kubernetes.api.model.NodeStatus
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.quarkus.runtime.Quarkus
import io.quarkus.runtime.QuarkusApplication
import io.quarkus.runtime.annotations.QuarkusMain
import kotlinx.coroutines.*
import org.jboss.logging.Logger
import org.threeten.extra.Interval
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.autoscaling.AutoScalingClient
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsRequest
import software.amazon.awssdk.services.autoscaling.model.SetDesiredCapacityRequest
import java.math.BigDecimal
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

data class Resources(val cpu: BigDecimal, val memory: BigDecimal, val pods: Long)
data class ScaleUpResponse(val scaleUp: Int, val nodeCount: Int)
data class AsgMinMaxDesired(val min: Int, val max: Int, val desired: Int)

// TODO: This is still proof of concept stage, don't use this in production without testing and tidy up
@QuarkusMain
class AppMain {
    companion object {
        private val LOG: Logger = Logger.getLogger(AppMain::class.java)
        val active = AtomicBoolean(true)
        private const val CPU_M_CONSTANT = 1000 // TODO: this is how many m there are per core need to find the code for this and make sure

        private val asgOneNodeCapacity: Resources
            get() {
                return Resources(
                    getCpuAmount(
                        Quantity.parse(System.getenv("NODE_CPU")?.toString() ?: "8")
                    ),
                    getMemAmount(
                        Quantity.parse(System.getenv("NODE_MEMORY")?.toString() ?: "16323915776")
                    ),
                    System.getenv("NODE_PODS")?.toLong() ?: 110
                )
            }

        // TODO: Move these somewhere nicer
        private val scaleUpFactor = System.getenv("SCALE_FACTOR")?.toInt() ?: 1
        private var currentScaleUpFactor = scaleUpFactor

        private val waitTimeBetweenScalingInMinutes = System.getenv("WAIT_TIME_IN_MINUTES")?.toLong() ?: 5
        private val dryRun = System.getenv("DRY_RUN")?.toBoolean() ?: true
        private val filterOutTaintedNodes = System.getenv("FILTER_OUT_TAINTED_NODES")?.toBoolean() ?: true
        private val onlyAddNodes = System.getenv("ONLY_ADD_NODES")?.toBoolean() ?: true

        private val awsRegion = Region.of(System.getenv("AWS_REGION") ?: "eu-west-2")!! // TODO: throw illegal arg exception if not set
        private val awsAsgName = System.getenv("AWS_ASG_NAME") ?: "asgmytest" // TODO: throw illegal arg exception if not set

        private val timeZoneId = try {
            ZoneId.of(System.getenv("TIME_ZONE_ID"))
        } catch (e: Exception) {null} ?: ZoneId.systemDefault()
        val clockGateway = JavaDateTimeClockGateway(timeZoneId)

        private val dailyDownScalePodsAndNodesTimeRange = DailyTimeRange.parse(System.getenv("DAILY_DOWNSCALE_PODS_AND_NODES_TIME_RANGE") ?: "20:00-07:00", clockGateway)
        private val dailyDownScaleScaleDownPods = System.getenv("DAILY_DOWNSCALE_SCALE_DOWN_PODS")?.toBoolean() ?: true
        private val dailyDownScaleScaleDownNodes = System.getenv("DAILY_DOWNSCALE_SCALE_DOWN_NODES")?.toBoolean() ?: true
        private val dailyDownScaleAutoScaleNodes = System.getenv("DAILY_DOWNSCALE_AUTO_SCALE_NODES")?.toBoolean() ?: false
        private val dailyDownScalePodsThreadCount = System.getenv("DAILY_DOWNSCALE_PODS_THREAD_COUNT")?.toInt() ?: 20
        private val dailyDownScaleNamespaceIgnoreList = (System.getenv("DAILY_DOWNSCALE_NAMESPACE_IGNORE_LIST") ?: "kube-system,istio-system,ingress-nginx,fleet-system,cert-manager,cattle-system,cattle-prometheus,kube-node-lease,kube-public,security-scan,cattle-monitoring-system").split(",")
        private val dailyDownScaleNodeCount = System.getenv("DAILY_DOWNSCALE_NODE_COUNT")?.toInt() ?: 3

        private val downscaleWeekendDaysList = (System.getenv("DOWNSCALE_WEEKEND_DAYS") ?: "SATURDAY,SUNDAY").toUpperCase().split(",")

        private val dailyBusyPeriod = System.getenv("DAILY_BUSY_PERIOD")?.toBoolean() ?: false
        private val dailyBusyPeriodTimeRange = DailyTimeRange.parse(System.getenv("DAILY_BUSY_PERIOD_TIME_RANGE") ?: "09:00-12:00", clockGateway)
        private val dailyBusyPeriodScaleUpFactor = System.getenv("DAILY_BUSY_PERIOD_SCALE_FACTOR")?.toInt() ?: 1

        private val namespaceUptimeScaling = System.getenv("NAMESPACE_UPTIME_SCALING")?.toBoolean() ?: true

        @JvmStatic
        fun main(vararg args: String) {
            Quarkus.run(MyApp::class.java, *args)
        }
        class MyApp : QuarkusApplication {
            @Throws(Exception::class)
            override fun run(vararg args: String): Int {
                setUpShutdownHook()
                LOG.info("Starting prescient-cluster-auto-scaler")
                LOG.info("AutoScalingGroup Single Node Capacity: $asgOneNodeCapacity")
                LOG.info("DRY RUN mode: $dryRun")
                LOG.info("Scale factor (Number of nodes to have ready for scheduling): $currentScaleUpFactor")
                LOG.info("Only add new nodes never delete nodes: $onlyAddNodes")
                LOG.info("Wait time between scaling events in minutes: $waitTimeBetweenScalingInMinutes")
                LOG.info("AWS Region: $awsRegion")
                LOG.info("AWS ASG: $awsAsgName")

                LOG.info("Daily down scale time: $dailyDownScalePodsAndNodesTimeRange")
                LOG.info("Daily down scale pods enabled: $dailyDownScaleScaleDownPods")
                LOG.info("Daily down scale nodes enabled: $dailyDownScaleScaleDownNodes")
                LOG.info("Daily down scale pod scaling thread count: $dailyDownScalePodsThreadCount")
                LOG.info("Daily down scale pod namespace ignore list: $dailyDownScaleNamespaceIgnoreList")
                LOG.info("Daily down scale target node count: $dailyDownScaleNodeCount")

                LOG.info("Weekend downscale day list: $downscaleWeekendDaysList")

                LOG.info("Daily busy period enabled: $dailyBusyPeriod")
                LOG.info("Daily busy period time range: $dailyBusyPeriodTimeRange")

                LOG.info("Namespace uptime scaling: $namespaceUptimeScaling")

                LOG.info("Timezone: $timeZoneId")

                val kubernetesClient = getAndConfigureKubernetesClient()
                val autoscalingClient = AutoScalingClient.builder()
                    .region(awsRegion)
                    .build()

                while (active.get()) {
                    val timerStartTime = ZonedDateTime.now(timeZoneId)
                    try {
                        val downScalingPodsMode =
                            dailyDownScaleScaleDownPods && dailyDownScalePodsAndNodesTimeRange.inWindow()
                        val downScalingNodesMode = downscaleWeekendDaysList.contains(timerStartTime.dayOfWeek.toString()) ||
                                dailyDownScaleScaleDownNodes && dailyDownScalePodsAndNodesTimeRange.inWindow()
                        val downScalingAutoScaleNodesMode = downscaleWeekendDaysList.contains(timerStartTime.dayOfWeek.toString()) ||
                                dailyDownScaleAutoScaleNodes && dailyDownScalePodsAndNodesTimeRange.inWindow()

                        val allowedNamespaces = getAllowedNamespaces(kubernetesClient)

                        if (downScalingPodsMode) {
                            LOG.info("Start Daily Cluster Deployment Down Scaling")
                            val coroutineDispatcher =
                                Executors.newFixedThreadPool(dailyDownScalePodsThreadCount).asCoroutineDispatcher()
                            scaleDeploymentsInNamespaces(
                                kubernetesClient,
                                logNamespaceNames(allowedNamespaces),
                                coroutineDispatcher)
                            {
                                0
                            }
                            LOG.info("Finished Daily Cluster Deployment Down Scaling")
                        }

                        val nowForUptimeScale = ZonedDateTime.now(timeZoneId)

                        val namespaceUptimeScalingMode = namespaceUptimeScaling && !downScalingPodsMode && !downScalingNodesMode
                        if (namespaceUptimeScalingMode){

                            val uptimeAnnotation = "prescient-cluster-autoscaler/uptime"
                            val annotatedNamespaces = allowedNamespaces
                                .filter { it.metadata.annotations.containsKey(uptimeAnnotation) }

                            val uptimeNamespaces = annotatedNamespaces.filter {
                                Interval.parse(it.metadata.annotations?.get(uptimeAnnotation))
                                    .contains(nowForUptimeScale.toInstant())
                            }

                            val downtimeNamespaces = annotatedNamespaces.filter {
                                !Interval.parse(it.metadata.annotations?.get(uptimeAnnotation))
                                    .contains(nowForUptimeScale.toInstant())
                            }

                            val coroutineDispatcher =
                                Executors.newFixedThreadPool(dailyDownScalePodsThreadCount).asCoroutineDispatcher()
                            LOG.info("Start Annotated Namespace Down Scaling")
                            scaleDeploymentsInNamespaces(
                                kubernetesClient,
                                logNamespaceNames(downtimeNamespaces),
                                coroutineDispatcher)
                            {
                                0
                            }

                            LOG.info("Start Annotated Namespace Up Scaling")
                            scaleDeploymentsInNamespaces(
                                kubernetesClient,
                                logNamespaceNames(uptimeNamespaces),
                                coroutineDispatcher)
                            {
                                it.metadata.annotations["prescient-cluster-autoscaler/uptime-desired-replicas"]?.toInt()
                            }
                        }

                        LOG.info("Start ASG scaling")
                        when {
                            downScalingNodesMode -> {
                                LOG.info("In downscale window start scaling down nodes")
                                asgTargetDesiredCapacityStrategy(dailyDownScaleNodeCount, autoscalingClient)
                            }
                            downScalingAutoScaleNodesMode -> {
                                LOG.info("In downscale window start auto scaling nodes")
                                val scaleUpResponse =
                                    calculateScaleUpFactor(kubernetesClient, currentScaleUpFactor, asgOneNodeCapacity, false)

                                LOG.info("Current kubernetes node count: ${scaleUpResponse.nodeCount}")

                                asgAdditiveDesiredCapacityStrategy(scaleUpResponse.scaleUp, autoscalingClient)
                            }
                            else -> {
                                LOG.info("Not in downscale window")

                                currentScaleUpFactor = scaleUpFactor
                                if (dailyBusyPeriod && dailyBusyPeriodTimeRange.inWindow()) {
                                    LOG.info("In busy period window")
                                    currentScaleUpFactor = dailyBusyPeriodScaleUpFactor
                                }
                                LOG.info("Current scale up factor: $currentScaleUpFactor")

                                LOG.info("Start aggregating resources")
                                val scaleUpResponse =
                                    calculateScaleUpFactor(kubernetesClient, currentScaleUpFactor, asgOneNodeCapacity, onlyAddNodes)

                                LOG.info("Current kubernetes node count: ${scaleUpResponse.nodeCount}")

                                asgAdditiveDesiredCapacityStrategy(scaleUpResponse.scaleUp, autoscalingClient)
                            }
                        }
                        LOG.info("Finished ASG scaling")
                    } catch (e : Exception) {
                        LOG.error(e, e)
                    }
                    WaitUntilGateway(clockGateway).waitUntilNext(
                        timerStartTime,
                        ChronoUnit.MINUTES,
                        waitTimeBetweenScalingInMinutes
                    )
                }
                Quarkus.waitForExit()
                return 0
            }

            private fun scaleDeploymentsInNamespaces(
                kubernetesClient: DefaultKubernetesClient,
                namespacesToScale: List<Namespace>,
                coroutineDispatcher: ExecutorCoroutineDispatcher,
                wait : Boolean = true,
                replicaCountStrategy: (d: Deployment) -> Int?
            ) {
                val deploymentsToScaleByNamespace: Map<Namespace, MutableList<Deployment>> = getDeploymentsInNamespace(namespacesToScale, kubernetesClient)

                runBlocking(coroutineDispatcher) {
                    deploymentsToScaleByNamespace.forEach { (ns, deps) ->
                        LOG.debug("Scaling namespace: ${ns.metadata.name}")
                        val scaledDownDeployments = Collections.synchronizedList<String>(mutableListOf())

                        deps.map {
                            launch {
                                try {
                                    if (it.kind != "Deployment") {
                                        LOG.warn("Kind not supported yet: ${it.kind} for ${it.metadata.name}")
                                        return@launch
                                    }
                                    val desiredReplicas = replicaCountStrategy(it)
                                    val logMessage =
                                        "Scaling deployment: ${it.metadata.name} in ${it.metadata.namespace} to $desiredReplicas"

                                    if ( desiredReplicas != null && it.spec.replicas != desiredReplicas) {
                                        if (dryRun) {
                                            LOG.trace("DRY RUN - $logMessage")
                                        } else {
                                            LOG.trace(logMessage)
                                            kubernetesClient.apps().deployments()
                                                .inNamespace(it.metadata.namespace)
                                                .withName(it.metadata.name)
                                                .scale(desiredReplicas, wait)
                                        }
                                        scaledDownDeployments.add(it.metadata.name)
                                    }
                                } catch (e: Exception) {
                                    LOG.error(e, e)
                                }
                            }
                        }.joinAll()
                        if (scaledDownDeployments.isNotEmpty()) {
                            LOG.info("${if (dryRun) "DRY RUN - " else ""}Scaled deployments in namespace ${ns.metadata.name}: $scaledDownDeployments")
                        }
                    }
                }
            }

            private fun getDeploymentsInNamespace(
                downscaleNamespaces: List<Namespace>,
                kubernetesClient: DefaultKubernetesClient
            ) = downscaleNamespaces.map {
                it to kubernetesClient.apps().deployments().inNamespace(it.metadata.name).list().items
            }.toMap()

            private fun getAllowedNamespaces(kubernetesClient: DefaultKubernetesClient): List<Namespace> {
                val namespaces: MutableList<Namespace> = kubernetesClient.namespaces().list().items
                val ignoredNamespaces =
                    namespaces.filter { dailyDownScaleNamespaceIgnoreList.contains(it.metadata.name) }
                LOG.debug("Ignoring namespaces: ${ignoredNamespaces.joinToString { it.metadata.name }}")

                return namespaces
                    .filter { !dailyDownScaleNamespaceIgnoreList.contains(it.metadata.name) }
            }

            private fun logNamespaceNames(allowedNamespaces: List<Namespace>): List<Namespace> {
                LOG.info("Namespace list: ${allowedNamespaces.joinToString { it.metadata.name }}")
                return allowedNamespaces
            }
        }

        private fun setUpShutdownHook() {
            Runtime.getRuntime().addShutdownHook(object : Thread() {
                override fun run() {
                    active.set(false)
                    LOG.info("Shutting down starting")
                    TimeUnit.SECONDS.sleep(1)
                    // TODO: handle shutdown properly
                    LOG.info("Shutting down finished")
                }
            })
        }

        private fun calculateScaleUpFactor(
            kubernetesClient: DefaultKubernetesClient,
            currentScaleUpFactor: Int,
            asgOneNodeCapacity: Resources,
            onlyAdd: Boolean
        ): ScaleUpResponse {
            val kubernetesRequestsStartTime = System.currentTimeMillis()
            val schedulableWorkerNodes = getSchedulableWorkerNodes(kubernetesClient)
            val nodeNames = schedulableWorkerNodes.map { node -> node.metadata.name }
            val activePodsByNode: Map<String, List<Pod>> = nodeNames.map { nodeName ->
                nodeName to getNodeNonTerminatedPodList(kubernetesClient, nodeName)
            }.toMap()
            val kubernetesRequestsEndTime = System.currentTimeMillis()
            LOG.info("Kubernetes client requests took (Milliseconds): ${kubernetesRequestsEndTime - kubernetesRequestsStartTime}")

            val nodeCapacity: MutableMap<String, Resources> = mutableMapOf()
            val nodeAllocatable: MutableMap<String, Resources> = mutableMapOf()
            val podRequested: MutableMap<String, Resources> = mutableMapOf()
            val podLimits: MutableMap<String, Resources> = mutableMapOf()

            schedulableWorkerNodes.forEach { node ->
                val nodeStatus = node?.status
                val nodeName = node.metadata.name
                nodeCapacity[nodeName] = Resources(
                    getCpuCapacityFromNodeStatus(nodeStatus),
                    getMemoryCapacityFromNodeStatus(nodeStatus),
                    getPodCapacityFromNodeStatus(nodeStatus)
                )
                nodeAllocatable[nodeName] = Resources(
                    getAllocatableCpuFromNodeStatus(nodeStatus),
                    getAllocatableMemoryFromNodeStatus(nodeStatus),
                    getAllocatablePodsFromNodeStatus(nodeStatus)
                )

                val activePodsOnNode = activePodsByNode[nodeName]
                podRequested[nodeName] = Resources(
                    getTotalCpuRequestsFromListOfPods(activePodsOnNode),
                    getTotalMemoryRequestsFromListOfPods(activePodsOnNode),
                    activePodsOnNode?.count()?.toLong() ?: 0
                )
                podLimits[nodeName] = Resources(
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
            LOG.info("Total requested resources: $totalRequested")
            LOG.info("Total available resources: $totalAvailable")

            val scaleUp =
                scalerStrategy.calculateScaleFactor(
                    currentScaleUpFactor, totalAvailable, asgOneNodeCapacity, onlyAdd
                )
            return ScaleUpResponse(scaleUp, nodeNames.count())
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
                ?: BigDecimal.ZERO) * BigDecimal(CPU_M_CONSTANT)

        private fun getMemoryCapacityFromNodeStatus(nodeStatus: NodeStatus?) =
            getMemAmount(nodeStatus?.capacity?.get("memory"))

        private fun getCpuCapacityFromNodeStatus(nodeStatus: NodeStatus?) =
            (nodeStatus?.capacity?.get("cpu")?.amount?.toInt()?.let { BigDecimal(it) } ?: BigDecimal.ZERO) * BigDecimal(
                CPU_M_CONSTANT
            )

        private fun getSchedulableWorkerNodes(it: DefaultKubernetesClient) =
            it.nodes()
                .withLabelIn("node-role.kubernetes.io/worker", "true")
                .list()
                .items
                .filter {!(it?.spec?.unschedulable ?: false)}
                .filter {!filterOutTaintedNodes || it?.spec?.taints?.isEmpty() ?: false}

        private fun getNodeNonTerminatedPodList(
            it: DefaultKubernetesClient,
            node: String?
        ) = it.pods()
            .inAnyNamespace()
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

            return when (q.format) {
                "" -> {
                    BigDecimal(q.amount.toInt() * CPU_M_CONSTANT)
                }
                "m" -> {
                    BigDecimal(q.amount.toInt())
                }
                else -> {
                    throw IllegalStateException()
                }
            }
        }

        private fun getMemAmount(q: Quantity?): BigDecimal {
            if (q == null) {
                return BigDecimal.ZERO
            }

            return Quantity.getAmountInBytes(q)
        }

        // TODO: Move this into its own class
        private fun asgAdditiveDesiredCapacityStrategy(scaleUp: Int, autoscaling: AutoScalingClient) {
            val currentAsgStatus = getCurrentAwsAsgCapacity(autoscaling)
            LOG.info("Current asg status: ${currentAsgStatus.desired}")
            LOG.info("Suggested asg scale up count: $scaleUp")

            val desiredCapacity = currentAsgStatus.desired + scaleUp
            val targetDesiredCapacity = max(desiredCapacity, currentAsgStatus.min)

            if (targetDesiredCapacity != currentAsgStatus.desired) {
                setAndCheckAWSAsgDesiredCapacity(targetDesiredCapacity, autoscaling)
            } else {
                LOG.info("No change required already at desired capacity: $desiredCapacity")
            }
        }

        private fun asgTargetDesiredCapacityStrategy(desiredCapacity: Int, autoscaling: AutoScalingClient) {
            val currentAsgStatus = getCurrentAwsAsgCapacity(autoscaling)
            LOG.info("Current asg status: $currentAsgStatus")
            val targetDesiredCapacity = max(desiredCapacity, currentAsgStatus.min)
            LOG.info("Target Desired Capacity: $targetDesiredCapacity")

            if (targetDesiredCapacity != currentAsgStatus.desired) {
                setAndCheckAWSAsgDesiredCapacity(targetDesiredCapacity, autoscaling)
            } else {
                LOG.info("No change required already at desired capacity: $desiredCapacity")
            }
        }

        private fun getCurrentAwsAsgCapacity(autoscaling: AutoScalingClient): AsgMinMaxDesired {
            val data = autoscaling.describeAutoScalingGroups(
                DescribeAutoScalingGroupsRequest.builder()
                    .autoScalingGroupNames(awsAsgName).build()
            ).autoScalingGroups().first()
            return AsgMinMaxDesired(data.minSize(), data.maxSize(), data.desiredCapacity())
        }

        private fun setAndCheckAWSAsgDesiredCapacity(
            desiredCapacity: Int,
            autoscalingClient: AutoScalingClient
        ) {
            if (dryRun) {
                LOG.info("DRY RUN - Setting desired capacity : $desiredCapacity")
            } else {
                LOG.info("Setting desired capacity : $desiredCapacity")
                autoscalingClient.setDesiredCapacity(
                    SetDesiredCapacityRequest.builder()
                        .autoScalingGroupName(awsAsgName)
                        .desiredCapacity(desiredCapacity)
                        .build()
                )
                // Check our updates
                val updatedAsgStatus = getCurrentAwsAsgCapacity(autoscalingClient)
                LOG.info("Updated desired capacity: $updatedAsgStatus")

                if (desiredCapacity != updatedAsgStatus.desired) {
                    LOG.error("Expected desired capacity of $desiredCapacity but only got $updatedAsgStatus")
                }
            }
        }
    }
}