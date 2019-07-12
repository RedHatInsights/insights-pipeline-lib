// Helpers involving jenkins slaves running on openshift


def withNode(Map parameters = [:], Closure body) {
    cloud = parameters.get('cloud', pipelineVars.defaultUICloud)
    def params = [
        cloud: cloud,
        namespace: cloud.equals(pipelineVars.defaultUICloud) ? pipelineVars.defaultUINameSpace : pipelineVars.defaultNameSpace,
        serviceAccount: pipelineVars.jenkinsSvcAccount,
        image: pipelineVars.defaultNodeImage,
        yaml: parameters.get('yaml'),
        workingDir: '/home/jenkins',
        requestCpu: '200m',
        limitCpu: '500m',
        requestMemory: '25Mi',
        limitMemory: '650Mi',
    ]
    params << parameters

    label = 'test-${UUID.randomUUID().toString()}'

    podParameters = [
        label: label,
        slaveConnectTimeout: 120,
        serviceAccount: params['serviceAccount'],
        cloud: params['cloud'],
        namespace: params['namespace'],
        annotations: [
           podAnnotation(key: 'job-name', value: "${env.JOB_NAME}"),
           podAnnotation(key: 'run-display-url', value: "${env.RUN_DISPLAY_URL}"),
        ]
    ]
    if (params['yaml']) {
        podParameters['yaml'] = readTrusted(yaml)
    } else {
        podParameters['containers'] = [
            containerTemplate(
                name: 'jnlp',
                image: params['image'],
                alwaysPullImage: true,
                args: '${computer.jnlpmac} ${computer.name}',
                resourceRequestCpu: params['requestCpu'],
                resourceLimitCpu: params['limitCpu'],
                resourceRequestMemory: params['requestMemory'],
                resourceLimitMemory: params['limitMemory'],
                workingDir: params['workingDir'],
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
    def params = [
        cloud: cloud,
        namespace: cloud.equals(pipelineVars.defaultUICloud) ? pipelineVars.defaultUINameSpace : pipelineVars.defaultNameSpace,
        serviceAccount: pipelineVars.jenkinsSvcAccount,
        slaveImage: pipelineVars.jenkinsSlaveIqeImage,
        seleniumImage: pipelineVars.seleniumImage,
        workingDir: '/tmp',
        requestCpu: '200m',
        limitCpu: '750m',
        requestMemory: '1Gi',
        limitMemory: '4Gi',
    ]
    params << parameters

    label = "test-${UUID.randomUUID().toString()}"

    podParameters = [
        label: label,
        slaveConnectTimeout: 120,
        serviceAccount: params['serviceAccount'],
        cloud: params["cloud"],
        namespace: params["namespace"],
        containers: [
            containerTemplate(
                name: 'jnlp',
                image: params['slaveImage'],
                alwaysPullImage: true,
                args: '${computer.jnlpmac} ${computer.name}',
                workingDir: params['workingDir'],
                resourceRequestCpu: params['requestCpu'],
                resourceLimitCpu: params['limitCpu'],
                resourceRequestMemory: params['requestMemory'],
                resourceLimitMemory: params['limitMemory'],
            ),
            containerTemplate(
                name: 'selenium',
                image: params['seleniumImage'],
                alwaysPullImage: true,
                workingDir: '',
                resourceRequestCpu: params['requestCpu'],
                resourceLimitCpu: params['limitCpu'],
                resourceRequestMemory: params['requestMemory'],
                resourceLimitMemory: params['limitMemory'],
            ),
        ],
        volumes: [
            emptyDirVolume(mountPath: '/dev/shm', memory: false),
        ],
        annotations: [
           podAnnotation(key: "job-name", value: "${env.JOB_NAME}"),
           podAnnotation(key: "run-display-url", value: "${env.RUN_DISPLAY_URL}"),
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
