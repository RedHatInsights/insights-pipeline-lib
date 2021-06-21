// Helpers for spinning up jenkins slaves running on OpenShift and other OpenShift utils


def getDefaultSlaveImage(String cloud) {
    if (cloud.equals(pipelineVars.upshiftCloud)) return pipelineVars.centralCIjenkinsSlaveImage
    else return pipelineVars.jenkinsSlaveImage
}


def getDefaultSlaveNamespace(String cloud) {
    if (cloud.equals(pipelineVars.upshiftCloud)) return pipelineVars.upshiftNameSpace
    else return pipelineVars.defaultNameSpace
}


private def setDevPiEnvVars(String image, String cloud, Collection envVars) {
    // If using the IQE tests core image on the external OpenShift deployment, use the devpi
    // server deployed in that external cluster
    if (image == pipelineVars.iqeCoreImage && cloud == pipelineVars.defaultCloud) {
        envVars.addAll([
            envVar(key: 'PIP_TRUSTED_HOST', value: 'devpi.devpi.svc'),
            envVar(key: 'PIP_INDEX_URL', value: 'http://devpi.devpi.svc:3141/root/psav'),
        ])
    }
}


private def getNow() {
    def now = new Date()
    return now.format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", TimeZone.getTimeZone('UTC'))
}


private def getContainerLogs(label, containerNames) {
    containerNames.each { containerName ->
        def fileName = "${label}-${containerName}.log"
        def logData = containerLog(name: containerName, tailingLines: 100, returnLog: true, limitBytes: 100000)
        writeFile(file: fileName, text: logData)
        archiveArtifacts(artifacts: fileName)
    }
}


private def runBody(Map podParameters, String label, String containerName, Closure body) {
    // each containerTemplate item in 'containers' is type:
    // org.jenkinsci.plugins.structs.describable.UninstantiatedDescribable
    def containerNames = podParameters['containers'].collect { containerDescribable ->
        containerDescribable.getArguments()['name']  // this requires white-listing
    }

    echo("[${getNow()}] Provisioning node...")
    podTemplate(podParameters) {
        node(label) {
            echo("[${getNow()}] Node provisioned")

            try {
                container(containerName) {
                    body()
                }
            }
            finally {
                // collect the tail of logs from each container to troubleshoot container crashes
                try {
                    getContainerLogs(label, containerNames)
                }
                catch(err) {
                    echo "Error collecting logs: ${err.toString()}"
                }
            }
        }
    }
}


def withNode(Map parameters = [:], Closure body) {
    /*
    Spins up a pod with 2 containers: jnlp, and specified 'image'
    */
    def image = parameters.get('image', pipelineVars.iqeCoreImage)
    def cloud = parameters.get('cloud', pipelineVars.defaultCloud)
    def jenkinsSlaveImage = parameters.get('jenkinsSlaveImage', getDefaultSlaveImage(cloud))
    def namespace = parameters.get('namespace', getDefaultSlaveNamespace(cloud))
    def requestCpu = parameters.get('resourceRequestCpu', "500m")
    def limitCpu = parameters.get('resourceLimitCpu', "500m")
    def requestMemory = parameters.get('resourceRequestMemory', "100Mi")
    def limitMemory = parameters.get('resourceLimitMemory', "1Gi")
    def jnlpRequestCpu = parameters.get('jnlpRequestCpu', "300m")
    def jnlpLimitCpu = parameters.get('jnlpLimitCpu', "300m")
    def jnlpRequestMemory = parameters.get('jnlpRequestMemory', "256Mi")
    def jnlpLimitMemory = parameters.get('jnlpLimitMemory', "512Mi")
    def buildingContainer = parameters.get('buildingContainer', "builder")
    def yaml = parameters.get('yaml')
    def envVars = parameters.get('envVars', [])
    def extraContainers = parameters.get('extraContainers', [])
    def volumes = parameters.get('volumes', [])

    def label = "node-${UUID.randomUUID().toString()}"

    def podParameters = [
        label: label,
        slaveConnectTimeout: 120,
        serviceAccount: pipelineVars.jenkinsSvcAccount,
        cloud: cloud,
        namespace: namespace,
        annotations: [
            podAnnotation(key: "job-name", value: "${env.JOB_NAME}"),
            podAnnotation(key: "run-display-url", value: "${env.RUN_DISPLAY_URL}"),
        ],
        volumes: volumes
    ]
    if (yaml) {
        podParameters['yaml'] = readTrusted(yaml)
    } else {
        setDevPiEnvVars(image, cloud, envVars)

        podParameters['containers'] = [
            containerTemplate(
                name: 'jnlp',
                image: jenkinsSlaveImage,
                args: '${computer.jnlpmac} ${computer.name}',
                resourceRequestCpu: jnlpRequestCpu,
                resourceLimitCpu: jnlpLimitCpu,
                resourceRequestMemory: jnlpRequestMemory,
                resourceLimitMemory: jnlpLimitMemory,
            ),
            containerTemplate(
                name: 'builder',
                ttyEnabled: true,
                command: 'cat',
                image: image,
                alwaysPullImage: true,
                resourceRequestCpu: requestCpu,
                resourceLimitCpu: limitCpu,
                resourceRequestMemory: requestMemory,
                resourceLimitMemory: limitMemory,
                envVars: envVars,
            ),
        ]
    }

    if (extraContainers) {
        podParameters['containers'].addAll(extraContainers)
    }

    runBody(podParameters, label, buildingContainer, body)
}


def withUINode(Map parameters = [:], Closure body) {
    /* 
    Spins up a pod with 3 containers: jnlp, selenium, and specified 'image'
    */
    def cloud = parameters.get('cloud', pipelineVars.upshiftCloud)
    def namespace = parameters.get('namespace', getDefaultSlaveNamespace(cloud))
    def slaveImage = parameters.get('slaveImage', getDefaultSlaveImage(cloud))
    def seleniumImage = parameters.get('seleniumImage', pipelineVars.seleniumImage)
    def image = parameters.get('image', pipelineVars.iqeCoreImage)
    def requestCpu = parameters.get('resourceRequestCpu', "200m")
    def limitCpu = parameters.get('resourceLimitCpu', "750m")
    def requestMemory = parameters.get('resourceRequestMemory', "256Mi")
    def limitMemory = parameters.get('resourceLimitMemory', "1Gi")
    def jnlpRequestCpu = parameters.get('jnlpRequestCpu', "100m")
    def jnlpLimitCpu = parameters.get('jnlpLimitCpu', "300m")
    def jnlpRequestMemory = parameters.get('jnlpRequestMemory', "256Mi")
    def jnlpLimitMemory = parameters.get('jnlpLimitMemory', "512Mi")
    def envVars = parameters.get('envVars', [])
    def extraContainers = parameters.get('extraContainers', [])
    def volumes = parameters.get('volumes', [])
    volumes.add(emptyDirVolume(mountPath: '/dev/shm', memory: true))

    def label = "node-${UUID.randomUUID().toString()}"

    setDevPiEnvVars(image, cloud, envVars)

    def podParameters = [
        label: label,
        slaveConnectTimeout: 120,
        serviceAccount: pipelineVars.jenkinsSvcAccount,
        cloud: cloud,
        namespace: namespace,
        containers: [
            containerTemplate(
                name: 'jnlp',
                image: slaveImage,
                args: '${computer.jnlpmac} ${computer.name}',
                resourceRequestCpu: jnlpRequestCpu,
                resourceLimitCpu: jnlpLimitCpu,
                resourceRequestMemory: jnlpRequestMemory,
                resourceLimitMemory: jnlpLimitMemory,
            ),
            containerTemplate(
                name: 'selenium',
                image: seleniumImage,
                resourceRequestCpu: '500m',
                resourceLimitCpu: '1',
                resourceRequestMemory: '512Mi',
                resourceLimitMemory: '3Gi',
                envVars: [
                    envVar(key: 'HOME', value: '/home/selenium'),
                ],
            ),
            containerTemplate(
                name: 'iqe',
                ttyEnabled: true,
                command: 'cat',
                image: image,
                alwaysPullImage: true,
                resourceRequestCpu: requestCpu,
                resourceLimitCpu: limitCpu,
                resourceRequestMemory: requestMemory,
                resourceLimitMemory: limitMemory,
                envVars: envVars,
            ),
        ],
        volumes: volumes,
        annotations: [
            podAnnotation(key: "job-name", value: "${env.JOB_NAME}"),
            podAnnotation(key: "run-display-url", value: "${env.RUN_DISPLAY_URL}"),
        ]
    ]

    // if yaml is used, the containers key will not be present
    if (podParameters.get('containers')) podParameters['containers'].addAll(extraContainers)

    runBody(podParameters, label, 'iqe', body)
}


def withNodeSelector(Map parameters = [:], Boolean ui, Closure body) {
    /* A wrapper that selects a different closure based on if 'ui' is true or false */
    if (ui) {
        withUINode(parameters) {
            body()
        }
    } else {
        withNode(parameters) {
            body()
        }   
    }
}


def withJnlpNode(Map parameters = [:], Closure body) {
    /*
    Spins up a pod with a single jnlp container
    */
    def cloud = parameters.get('cloud', pipelineVars.defaultCloud)
    def image = parameters.get('image', getDefaultSlaveImage(cloud))
    def namespace = parameters.get('namespace', getDefaultSlaveNamespace(cloud))
    def jnlpRequestCpu = parameters.get('jnlpRequestCpu', "100m")
    def jnlpLimitCpu = parameters.get('jnlpLimitCpu', "300m")
    def jnlpRequestMemory = parameters.get('jnlpRequestMemory', "100Mi")
    def jnlpLimitMemory = parameters.get('jnlpLimitMemory', "512Mi")
    def yaml = parameters.get('yaml')
    def envVars = parameters.get('envVars', [])
    def extraContainers = parameters.get('extraContainers', [])
    def volumes = parameters.get('volumes', [])

    def label = "node-${UUID.randomUUID().toString()}"

    def podParameters = [
        label: label,
        slaveConnectTimeout: 120,
        serviceAccount: pipelineVars.jenkinsSvcAccount,
        cloud: cloud,
        namespace: namespace,
        annotations: [
            podAnnotation(key: "job-name", value: "${env.JOB_NAME}"),
            podAnnotation(key: "run-display-url", value: "${env.RUN_DISPLAY_URL}"),
        ],
        volumes: volumes
    ]
    if (yaml) {
        podParameters['yaml'] = readTrusted(yaml)
    } else {
        setDevPiEnvVars(image, cloud, envVars)

        podParameters['containers'] = [
            containerTemplate(
                name: 'jnlp',
                image: image,
                args: '${computer.jnlpmac} ${computer.name}',
                resourceRequestCpu: jnlpRequestCpu,
                resourceLimitCpu: jnlpLimitCpu,
                resourceRequestMemory: jnlpRequestMemory,
                resourceLimitMemory: jnlpLimitMemory,
            )
        ]
    }

    if (extraContainers) {
        podParameters['containers'].addAll(extraContainers)
    }

    runBody(podParameters, label, 'jnlp', body)
}


def collectLogs(parameters = [:]) {
    /* Collects all logs from all pods running in 'project' and stores them as artifacts */
    def project = parameters['project']

    stage("Collect logs") {
        try {
            sh "oc project ${project}"
            sh '''
                mkdir -p applogs/
                PODS=$(oc get pods -o jsonpath='{range .items[*]}{.metadata.name}{"\\n"}')
                for pod in $PODS; do
                    CONTAINERS=$(oc get pod $pod -o jsonpath='{range .spec.containers[*]}{.name}{"\\n"}' || echo "")
                    if [ -z "$CONTAINERS" ]; then
                        echo "get logs: pod $pod not found"
                    fi;
                    for container in $CONTAINERS; do
                        oc logs $pod -c $container > applogs/${pod}_${container}.log || echo "get logs: ${pod}_${container} failed."
                        echo "Saved logs for $pod container $container"
                    done
                done
            '''
            sh "oc get --export all -o yaml > oc_export_all.yaml"
            archiveArtifacts "oc_export_all.yaml"
            archiveArtifacts "applogs/*.log"
        } catch (err) {
            def errString = err.toString()
            echo "Collecting logs failed: ${errString}"
        }
    }
}


def waitForDeployment(parameters = [:]) {
    /** 
    * Waits until source code in a pod has a certain commit and then waits until it is fully
    * deployed. It assumes that pods and replication controllers have one common label e.g.:
    *   "app: compliance-backend".
    */
    def cluster = parameters["cluster"]
    def credentials = parameters["credentials"]
    def project = parameters["project"]
    def minutes = parameters["minutes"]
    def label = parameters["label"]
    def value = parameters["value"]
    def gitCommit = parameters["gitCommit"]
    openshift.withCluster(cluster) {
        openshift.withCredentials(credentials) {
            openshift.withProject(project) {
                timeout(minutes) {
                    finished = false
                    waitUntil {
                        try {
                            def pod = openshift.selector("pod", [(label): value]).names()[0]
                            def podGitCommit = openshift.rsh("$pod git rev-parse HEAD").out.trim()
                            // Once a new code appeared in a pod we need to wait until it will be
                            // fully deployed.
                            if (podGitCommit == gitCommit) {
                                echo "Pod ($pod) with commit hash $podGitCommit has been spawned"
                                waitUntil {
                                    // Get the latest replication controller and check its status
                                    def lastRc = openshift.selector(
                                        "rc", [(label): value]
                                    ).objects()[-1]

                                    finished = lastRc.status.replicas == lastRc.status.readyReplicas
                                    if (finished) {
                                        echo "Pod ($pod) has been deployed"
                                    }
                                    return finished
                                }
                            }
                            return finished
                        } catch(err) {
                            echo "Error occured: ${err.getMessage()}"
                            return false
                        }
                    }
                }
            }
        }
    }
}
