// Helpers involving jenkins slaves running on openshift


def withNode(Map parameters = [:], Closure body) {
    image = parameters.get('image', pipelineVars.defaultNodeImage)
    cloud = parameters.get('cloud', pipelineVars.defaultCloud)
    namespace = parameters.get(
        'namespace',
        cloud.equals(pipelineVars.defaultUICloud) ? pipelineVars.defaultUINameSpace : pipelineVars.defaultNameSpace
    )
    requestCpu = parameters.get('resourceRequestCpu', "200m")
    limitCpu = parameters.get('resourceLimitCpu', "500m")
    requestMemory = parameters.get('resourceRequestMemory', "256Mi")
    limitMemory = parameters.get('resourceLimitMemory', "650Mi")
    yaml = parameters.get('yaml')
    workingDir = parameters.get('workingDir', "/home/jenkins")

    label = "test-${UUID.randomUUID().toString()}"

    podParameters = [
        label: label,
        slaveConnectTimeout: 120,
        serviceAccount: pipelineVars.jenkinsSvcAccount,
        cloud: cloud,
        namespace: namespace,
        annotations: [
           podAnnotation(key: "job-name", value: "${env.JOB_NAME}"),
           podAnnotation(key: "run-display-url", value: "${env.RUN_DISPLAY_URL}"),
        ]
    ]
    if (yaml) {
        podParameters['yaml'] = readTrusted(yaml)
    } else {
        podParameters['containers'] = [
            containerTemplate(
                name: 'jnlp',
                image: image,
                alwaysPullImage: true,
                args: '${computer.jnlpmac} ${computer.name}',
                resourceRequestCpu: requestCpu,
                resourceLimitCpu: limitCpu,
                resourceRequestMemory: requestMemory,
                resourceLimitMemory: limitMemory,
                workingDir: workingDir,
                envVars: [
                    envVar(key: 'LC_ALL', value: 'en_US.utf-8'),
                    envVar(key: 'LANG', value: 'en_US.utf-8'),
                ],
            ),
        ]
    }

    podTemplate(podParameters) {
        node(label) {
            body()
        }
    }
}


def withUINode(Map parameters = [:], Closure body) {
    cloud = parameters.get('cloud', pipelineVars.defaultUICloud)
    namespace = parameters.get(
        'namespace',
        cloud.equals(pipelineVars.defaultUICloud) ? pipelineVars.defaultUINameSpace : pipelineVars.defaultNameSpace
    )
    slaveImage = parameters.get('slaveImage', pipelineVars.centralCIjenkinsSlaveImage)
    seleniumImage = parameters.get('seleniumImage', pipelineVars.seleniumImage)
    iqeCoreImage = parameters.get('iqeCoreImage', pipelineVars.iqeCoreImage)
    requestCpu = parameters.get('resourceRequestCpu', "200m")
    limitCpu = parameters.get('resourceLimitCpu', "750m")
    requestMemory = parameters.get('resourceRequestMemory', "512Mi")
    limitMemory = parameters.get('resourceLimitMemory', "2Gi")

    label = "test-${UUID.randomUUID().toString()}"

    podParameters = [
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
                resourceRequestCpu: '100m',
                resourceLimitCpu: '300m',
                resourceRequestMemory: '512Mi',
                resourceLimitMemory: '1Gi',
            ),
            containerTemplate(
                name: 'selenium',
                image: seleniumImage,
                resourceRequestCpu: '500m',
                resourceLimitCpu: '1',
                resourceRequestMemory: '1Gi',
                resourceLimitMemory: '4Gi',
                envVars: [
                    envVar(key: 'HOME', value: '/home/selenium'),
                ],
            ),
            containerTemplate(
                name: 'iqe',
                ttyEnabled: true,
                command: 'cat',
                image: iqeCoreImage,
                alwaysPullImage: true,
                resourceRequestCpu: requestCpu,
                resourceLimitCpu: limitCpu,
                resourceRequestMemory: requestMemory,
                resourceLimitMemory: limitMemory,
            ),
        ],
        volumes: [
            emptyDirVolume(mountPath: '/dev/shm', memory: true),
        ],
        annotations: [
           podAnnotation(key: "job-name", value: "${env.JOB_NAME}"),
           podAnnotation(key: "run-display-url", value: "${env.RUN_DISPLAY_URL}"),
        ]
    ]

    podTemplate(podParameters) {
        node(label) {
            container('iqe') {
                body()
            }
        }
    }
}

def collectLogs(parameters = [:]) {
    project = parameters['project']

    stage("Collect logs") {
        try {
            sh "oc project ${project}"
            sh '''
                mkdir -p applogs/
                PODS=$(oc get pods -o jsonpath='{range .items[*]}{.metadata.name}{"\\n"}')
                for pod in $PODS; do
                    CONTAINERS=$(oc get pod $pod -o jsonpath='{range .spec.containers[*]}{.name}{"\\n"}')
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
            errString = err.toString()
            echo "Collecting logs failed: ${errString}"
        }
    }
}
