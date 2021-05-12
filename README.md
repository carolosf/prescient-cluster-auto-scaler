# prescient-cluster-auto-scaler project

This project scales kubernetes clusters automatically so that you always have at least x times the resources you need available.
This is useful for developer preview environments.

This project's default settings don't scale down nodes only grows the number of nodes in the auto scaling group to avoid disrupting developer environments.

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: https://quarkus.io/ .

## TODO
- Delete nodes that failed to start/unresponsive nodes
- Potentially cordoning and draining nodes during down scale

## IAM Policy
```
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "autoscaling:DescribeAutoScalingGroups",
                "autoscaling:DescribeAutoScalingInstances",
                "autoscaling:DescribeLaunchConfigurations",
                "autoscaling:SetDesiredCapacity",
                "autoscaling:TerminateInstanceInAutoScalingGroup"
            ],
            "Resource": ["*"]
        }
    ]
}
```

## Environment variables
| Name        | Default | Description |
| :---        | :---    | :---        |
|DRY_RUN      | true    | If true no autoscaling will actually occur, log will say DRY RUN on setting autoscaler desired capacity |
|NODE_CPU     | 8       | Available CPU of one node in your auto scaling group in CPU cores or millicpus when suffixed with m |
|NODE_MEMORY  | 16323915776  | Available memory in bytes of one node in your autoscaling group (supported values Ki etc. of Quantity type - use `kubectl describe node` check under node capacity to see what value you should use )  |
|NODE_PODS     | 110 | How many pods a node in your autoscaling group can support - no support for pods per core for now |
|WAIT_TIME_IN_MINUTES | 5 | How frequently prescient cluster autoscaler should try to calculate used resources and scale up. This should be larger than any scale time protection you have |
|SCALE_FACTOR| 1 | How many extra nodes prescient cluster autoscaler will try to have always available for the cluster to use|
|FILTER_OUT_TAINTED_NODES | true | Tainted nodes are harder to calculate resources for - so this will ignore tainted nodes and their pods|
|ONLY_ADD_NODES | true | Prescient cluster autoscaler doesn't try to reschedule pods when a node is terminating - so this makes sure it doesn't try make the autoscaling group smaller, it will only add new nodes to the autoscaling group never delete nodes  |
|AWS_REGION  | eu-west-2 | Currently only supports AWS cloud provider autoscaling groups so this sets the AWS region of your autoscaling group |
|AWS_ASG_NAME  | asgmytest | This is the name of the autoscaling group to scale |
|DAILY_DOWNSCALE_TIME_RANGE| 20:00-07:00 | This is the daily time that prescient cluster autoscaler will try downscale pods and then downscale nodes. |
|DAILY_DOWNSCALE_SCALE_DOWN_PODS| true | This will attempt to scale down pods to 0 in all namespaces except those in DAILY_DOWNSCALE_NAMESPACE_IGNORE_LIST |
|DAILY_DOWNSCALE_PODS_THREAD_COUNT| 20 | Number of namespaces to scale in parallel |
|DAILY_DOWNSCALE_NAMESPACE_IGNORE_LIST| "kube-system,istio-system,ingress-nginx,fleet-system,cert-manager,cattle-system,cattle-prometheus,kube-node-lease,kube-public,security-scan,cattle-monitoring-system" | Ignore scaling down pods in these namespaces separate namespaces with comma character |
|DAILY_DOWNSCALE_SCALE_DOWN_NODES| true | This will attempt to scale down nodes it won't drain and cordon nodes for deletion it will just set the autoscaling group to the specified number of nodes |
|DAILY_DOWNSCALE_AUTO_SCALE_NODES| false | This will attempt to auto scale nodes it won't drain and cordon nodes for deletion it will just set the autoscaling group to the specified number of nodes during the downscale window |
|DAILY_DOWNSCALE_NODE_COUNT| 3 | This is the target number of nodes when downscaling occurs for the day. If lower than autoscaling group minimum, will honor ASG minimum |
|TIME_ZONE_ID | System Time Zone | This is the timezone to use for downscaling time range and other time operations. The default is whatever timezone the system is set to. Example format Europe/London. https://docs.oracle.com/javase/8/docs/api/java/time/ZoneId.html |
|DOWNSCALE_WEEKEND_DAYS|SATURDAY,SUNDAY|This downscales to DAILY_DOWNSCALE_NODE_COUNT on the weekend if these values are set|
|DAILY_BUSY_PERIOD|false| This lets you set a different scaling factor on daily busy periods|
|DAILY_BUSY_PERIOD_TIME_RANGE|09:00-12:00|This is the time range of the busy period|
|DAILY_BUSY_PERIOD_SCALE_FACTOR|1|This is the scale factor of the busy period|
|NAMESPACE_UPTIME_SCALING|true|If not in the downscale time range then this scales namespaces up if the "prescient-cluster-autoscaler/uptime" annotation is present with a value in ISO8601 interval format. See below for more details. |
|NAMESPACE_UPTIME_SCALING_ALLOW_HIGHER_REPLICAS|true|Allows users to manually set a higher replica count during their uptime window. Gets set to 0 when uptime window expires.|

## Sample Kubernetes Cluster Role and Binding
```
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: prescient-cluster-autoscaler
rules:
  - apiGroups:
      - ""
    resources:
      - namespaces
      - nodes
      - pods
    verbs:
      - watch
      - list
      - get
  - apiGroups:
      - apps
    resources:
      - deployments
    verbs:
      - get
      - watch
      - list
      - update
      - patch
```
```
apiVersion: v1
kind: ServiceAccount
metadata:
  name: prescient-cluster-autoscaler
  namespace: kube-system
```
```
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: prescient-cluster-autoscaler
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: prescient-cluster-autoscaler
subjects:
  - kind: ServiceAccount
    name: prescient-cluster-autoscaler
    namespace: kube-system
```
```
apiVersion: {{ template "deployment.apiVersion" . }}
kind: Deployment
metadata:
  name: prescient-cluster-autoscaler
  namespace: kube-system
spec:
  replicas: 1
  selector:
    matchLabels:
      app: prescient-cluster-autoscaler
  template:
    metadata:
      labels:
        app: prescient-cluster-autoscaler
    spec:
      priorityClassName: "system-cluster-critical"
      containers:
        - name: prescient-cluster-autoscaler
          image: carolosf/prescient-cluster-autoscaler
          env:
            - name: AWS_ASG_NAME
              value: theNameOfMyAWSASG
            - name: AWS_REGION
              value: eu-west-2
            - name: DRY_RUN
              value: "true"
            - name: FILTER_OUT_TAINTED_NODES
              value: "true"
            - name: NODE_CPU
              value: "16"
            - name: NODE_MEMORY
              value: 64645380Ki
            - name: NODE_PODS
              value: "110"
            - name: SCALE_FACTOR
              value: 10
            - name: WAIT_TIME_IN_MINUTES
              value: 10
            - name: ONLY_ADD_NODES
              value: true
```
You need to create a namespace or install in kube-system

## Namespace up time scaling
You might want to scale up a particular namespace to run during an uptime window.

NOTE: name space uptime scaling during the cluster downtime window is not supported.

The up time interval is defined using an annotation on the namespace called "prescient-cluster-autoscaler/uptime" with a value in the ISO8601 Time Intervals format.

e.g.
"prescient-cluster-autoscaler/uptime": "2021-03-01T13:00:00Z/2021-05-11T15:30:00Z"

More information available here: https://www.threeten.org/threeten-extra/apidocs/org.threeten.extra/org/threeten/extra/Interval.html#parse(java.lang.CharSequence)

You should set the "prescient-cluster-autoscaler/uptime-desired-replicas" annotation on deployments to the value you need to set desired replicas to.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:
```shell script
./gradlew quarkusDev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at http://localhost:8080/q/dev/.

## Packaging and running the application

The application can be packaged using:
```shell script
./gradlew build
```
It produces the `quarkus-run.jar` file in the `build/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `build/quarkus-app/lib/` directory.

If you want to build an _über-jar_, execute the following command:
```shell script
./gradlew build -Dquarkus.package.type=uber-jar
```

The application is now runnable using `java -jar build/quarkus-app/quarkus-run.jar`.

## Creating a native executable

You can create a native executable using: 
```shell script
./gradlew build -Dquarkus.package.type=native
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using: 
```shell script
./gradlew build -Dquarkus.package.type=native -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./build/prescient-cluster-auto-scaler-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult https://quarkus.io/guides/gradle-tooling.



