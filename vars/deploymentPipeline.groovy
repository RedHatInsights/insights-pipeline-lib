// Set up the job parameters
private def getJobParams(envs, svcs) {
    p = []
    svcs.each { key, data ->
        p.add([$class: 'BooleanParameterDefinition', name: data['paramName'], defaultValue: true, description: "Deploy/promote ${data['displayName']}"])
    }

    choices = []
    envs.each { key, data ->
        choices.add(data['env'])
    }
    p.add([$class: 'ChoiceParameterDefinition', name: 'ENV', choices: choices, description: 'The target environment'])

    return p
}


// Parse the parameters for a specific job run
private def parseParams(envs, svcs) {
    imagesToCopy = []
    servicesToSkip = envs[params.ENV].get('skip', [])

    if (envs[params.ENV]['copyImages']) {
        svcs.each { key, data ->
            // if the service was checked, add its image to the list of images we will copy
            if (params.get(data['paramName'])) imagesToCopy.add(data['srcImage'])
        }
    }

    if (envs[params.ENV]['deployServices']) {
        svcs.each { key, data ->
            // if a service was not checked, add it to the list of services to skip, but only
            // if 'promoteImageOnly' is false (because this would indicate deployment doesn't apply for this component
            if (!params.get(data['paramName']) && !data.get('promoteImageOnly')) servicesToSkip.add(data['templateName'])
        }
    }

    return [envConfig: envs[params.ENV], imagesToCopy: imagesToCopy, servicesToSkip: servicesToSkip, deployServices: envs[params.ENV]['deployServices']]
}

// Create a deployment pipeline job given an environment and service config
def call(p = [:]) {
    envs = p['environments']
    svcs = p['services']
    extraParams = p['extraParams'] ?: []

    properties([parameters(getJobParams(envs, svcs) + extraParams)])
    parsed = parseParams(envs, svcs)
    imagesToCopy = parsed['imagesToCopy']
    servicesToSkip = parsed['servicesToSkip']
    envConfig = parsed['envConfig']
    deployServices = parsed['deployServices']

    echo "imagesToCopy:   ${imagesToCopy}"
    echo "servicesToSkip: ${servicesToSkip}"
    echo "envConfig:      ${envConfig}"

    // For build #1, only load the pipeline and exit
    // This is so the next time the job is built, "Build with parameters" will be available
    if (env.BUILD_NUMBER.toString() == "1") {
        echo "Initial run, loaded pipeline job and now exiting."
        currentBuild.description = "loaded params"
        return
    }

    currentBuild.description = "env: ${envConfig['env']}"

    openShift.withNode(defaults: true) {
        if (imagesToCopy) {
            stage('Copy images') {
                promoteImages(
                    srcImages: parsed['imagesToCopy'],
                    dstProject: envConfig['project'],
                    dstSaUsername: envConfig['saUsername'],
                    dstSaTokenCredentialsId: envConfig['saTokenCredentialsId'],
                    dstCluster: envConfig['cluster']
                )
            }
        }

        stage('Login as deployer account') {
            withCredentials([string(credentialsId: envConfig['saTokenCredentialsId'], variable: 'TOKEN')]) {
                sh "oc login https://${envConfig['cluster']} --token=${TOKEN}"
            }

            sh "oc project ${envConfig['project']}"
        }

        if (deployServices) {
            stage('Run e2e-deploy') {
                deployServiceSet(
                    serviceSet: envConfig['serviceSet'],
                    skip: servicesToSkip,
                    env: envConfig['env'],
                    project: envConfig['project'],
                    secretsSrcProject: envConfig['secretsSrcProject'],
                )
            }
        }
    }
}
