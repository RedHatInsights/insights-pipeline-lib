// Helpers involving jenkins slaves running on openshift


def withNode(parameters = [:], Closure body = null) {
    // parameters must always be present when calling 'withNode', so pass 'defaults: true' if you want defaults
    defaults = parameters.get('defaults')

    image = parameters.get('image', pipelineVars.defaultNodeImage)
    namespace = parameters.get('namespace', pipelineVars.defaultNameSpace)
    requestCpu = parameters.get('resourceRequestCpu', "200m")
    limitCpu = parameters.get('resourceLimitCpu', "500m")
    requestMemory = parameters.get('resourceRequestMemory', "256Mi")
    limitMemory = parameters.get('resourceLimitMemory', "650Mi")
    cloud = parameters.get('cloud', "openshift")
    yaml = parameters.get('yaml')
    workingDir = parameters.get('workingDir', "/home/jenkins")

    label = "test-${UUID.randomUUID().toString()}"

    podParameters = [
        label: label,
        slaveConnectTimeout: 120,
        serviceAccount: pipelineVars.jenkinsSvcAccount,
        cloud: cloud,
        namespace: namespace
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


def withUINode(Map parameters = [:], Closure body = null) {
    namespace = parameters.get('namespace', 'insights-qe-ci')
    cloud = parameters.get('cloud', pipelineVars.defaultUICloud)
    slaveImage = parameters.get('slaveImage', pipelineVars.jenkinsSlaveIqeImage)
    seleniumImage = parameters.get('seleniumImage', pipelineVars.seleniumImage)
    workingDir = parameters.get('workingDir', '/tmp')

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
                alwaysPullImage: true,
                args: '${computer.jnlpmac} ${computer.name}',
                workingDir: workingDir
            ),
            containerTemplate(
                name: 'selenium',
                image: seleniumImage,
                alwaysPullImage: true,
                workingDir: ''
            ),
        ],
        volumes: [
            emptyDirVolume(mountPath: '/dev/shm', memory: false),
        ]
    ]

    podTemplate(podParameters) {
        node(label) {
            body()
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
            sh "oc export all -o yaml > oc_export_all.yaml"
            archiveArtifacts "oc_export_all.yaml"
            archiveArtifacts "applogs/*.log"
        } catch (err) {
            errString = err.toString()
            echo "Collecting logs failed: ${errString}"
        }
    }
}
