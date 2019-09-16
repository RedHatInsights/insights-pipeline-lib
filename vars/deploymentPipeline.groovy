
// Auto-generate the param name for a service if it was not specified
private def getParamNameForSvcKey(String key, Map svcData) {
    return svcData.get('paramName', "DEPLOY_${key.toString().toUpperCase()}")
}


// Set up the job parameters
private def getJobParams(envs, svcs) {
    p = []
    svcs.each { key, data ->
        def paramName = getParamNameForSvcKey(key, data)
        def displayName = data.get('displayName', "${key.toString()}")
        p.add([$class: 'BooleanParameterDefinition', name: paramName, defaultValue: true, description: "Deploy/promote ${displayName}"])
    }

    choices = []
    envs.each { key, data ->
        choices.add(data['env'])
    }
    p.add([$class: 'ChoiceParameterDefinition', name: 'ENV', choices: choices, description: 'The target environment'])
    p.add([$class: 'BooleanParameterDefinition', name: 'RELOAD', defaultValue: false, description: "Do nothing, simply re-load this job's groovy file"])

    return p
}


// Parse the parameters for a specific job run
private def parseParams(envs, svcs) {
    imagesToCopy = []
    echo "Job params: ${params.toString()}"
    servicesToSkip = envs[params.ENV].get('skip', [])

    svcs.each { key, data ->
        paramName = getParamNameForSvcKey(key, data)
        echo "Checking if ${paramName} is checked and should be deployed..."
        boxChecked = params.get(paramName.toString())
        promoteImageOnly = data.get('promoteImageOnly')
        disableImageCopy = data.get('disableImageCopy')
        copyImages = envs[params.ENV]['copyImages']
        deployServices = envs[params.ENV]['deployServices']

        echo "${key} boxChecked: ${boxChecked}, promoteImageOnly: ${promoteImageOnly}, disableImageCopy: ${disableImageCopy}"

        if (copyImages) {
            // if the service was checked, add its image to the list of images we will copy
            if (boxChecked) {
                if (!disableImageCopy) imagesToCopy.add(data['srcImage'])
            }
        }

        if (deployServices) {
            // if a service was not checked, add it to the list of services to skip, but only
            // if 'promoteImageOnly' is false (because this would indicate deployment doesn't apply for this component)
            if (!boxChecked && !promoteImageOnly) servicesToSkip.add(data['templateName'])
        }
    }

    return [envConfig: envs[params.ENV], imagesToCopy: imagesToCopy, servicesToSkip: servicesToSkip, deployServices: envs[params.ENV]['deployServices']]
}


def runDeploy(parsed) {
    imagesToCopy = parsed['imagesToCopy']
    servicesToSkip = parsed['servicesToSkip']
    envConfig = parsed['envConfig']
    deployServices = parsed['deployServices']

    echo "imagesToCopy:   ${imagesToCopy}, servicesToSkip: ${servicesToSkip}"
    echo "envConfig:      ${envConfig}"

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


def sendSlackMsg(msg, color = null) {
    if (slackChannel && slackUrl) {
        slackSend(
            baseUrl: slackUrl,
            botUser: true,
            channel: slackChannel,
            color: color,
            message: msg
        )
    }
}


// Create a deployment pipeline job given an environment and service config
def call(p = [:]) {
    envs = p['environments']
    svcs = p['services']
    extraParams = p['extraParams'] ?: []
    slackChannel = p.get('slackChannel')
    slackUrl = p.get('slackUrl')

    properties([parameters(getJobParams(envs, svcs) + extraParams)])
    parsed = parseParams(envs, svcs)

    // Exit the job if the "reload" box was checked
    if (params.RELOAD) {
        echo "Job is only reloading"
        currentBuild.description = "reload"
        return
    }

    // For build #1, only load the pipeline and exit
    // This is so the next time the job is built, "Build with parameters" will be available
    if (env.BUILD_NUMBER.toString() == "1") {
        echo "Initial run, loaded pipeline job and now exiting."
        currentBuild.description = "loaded params"
        return
    }

    sendSlackMsg("${env.JOB_NAME} started for env ${parsed['envConfig']['env']}")

    try {
        runDeploy(parsed)
    } catch (err) {
        sendSlackMsg("${env.JOB_NAME} failed for env ${parsed['envConfig']['env']} -- see ${env.RUN_DISPLAY_URL}", "danger")
        throw err
    }
    sendSlackMsg("${env.JOB_NAME} successful for env ${parsed['envConfig']['env']}", "good")
}
