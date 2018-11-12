// Helpers involving jenkins slaves running on openshift


def withNode(Map parameters = [:], Closure body = null) {
    image = parameters.get('image', pipelineVars.defaultNodeImage)
    namespace = parameters.get('namespace', pipelineVars.defaultNameSpace)

    label = "test-${UUID.randomUUID().toString()}"

    podTemplate(
        label: label,
        slaveConnectTimeout: 120,
        serviceAccount: pipelineVars.jenkinsSvcAccount,
        cloud: 'openshift',
        namespace: namespace,
        containers: [
            containerTemplate(
                name: 'jnlp',
                image: image,
                alwaysPullImage: true,
                args: '${computer.jnlpmac} ${computer.name}',
                resourceRequestCpu: '200m',
                resourceLimitCpu: '500m',
                resourceRequestMemory: '256Mi',
                resourceLimitMemory: '650Mi',
                envVars: [
                    envVar(key: 'LC_ALL', value: 'en_US.utf-8'),
                    envVar(key: 'LANG', value: 'en_US.utf-8'),
                ],
            ),
        ]
    ) {
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
