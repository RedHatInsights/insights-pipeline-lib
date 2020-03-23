/**
 * Pipeline job shared by app teams to deploy their service
 *
 * Each team creates their own config of "environments" and "services", then calls this function
 *
 * An example job config is found at:
 *  https://github.com/RedHatInsights/e2e-deploy/blob/master/jenkins/deploy/template.groovy
 */


private def getParamNameForSvcKey(String key, Map svcData) {
    // Auto-generate the param name for a service if it was not specified
    return svcData.get('paramName', "DEPLOY_${key.toString().toUpperCase()}")
}


private def getJobParams(envs, svcs) {
    // Set up the job parameters
    p = []
    svcs.each { key, data ->
        def paramName = getParamNameForSvcKey(key, data)
        def displayName = data.get('displayName', "${key.toString()}")
        Boolean checkedByDefault = data.get('checkedByDefault', true)
        p.add([
            $class: 'BooleanParameterDefinition',
            name: paramName, defaultValue: checkedByDefault, description: "Deploy/promote ${displayName}"
        ])
    }

    choices = []
    envs.each { key, data ->
        choices.add(data['env'])
    }
    p.add([
        $class: 'ChoiceParameterDefinition',
        name: 'ENV', choices: choices, description: 'The target environment'
    ])
    p.add([
        $class: 'BooleanParameterDefinition',
        name: 'RELOAD',
        defaultValue: false,
        description: "Do nothing, simply re-load this job's groovy file"
    ])

    return p
}


// Parse the selected parameters when the job is run
private def parseParams(envs, svcs) {
    imagesToCopy = []  // a list of Maps with key = srcImage, value = dstImage
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

        echo(
            "${key} boxChecked: ${boxChecked}, promoteImageOnly: ${promoteImageOnly}, " +
            "disableImageCopy: ${disableImageCopy}"
        )

        // if the service was checked, add its image to the list of images we will copy
        if (copyImages && !disableImageCopy && boxChecked) {
            def srcImage = data['srcImage']
            def imgMap = [srcImage: data.get('dstImage', srcImage)]
            imagesToCopy.add(imgMap)
        }

        // if a service was not checked, add it to the list of services to skip, but only
        // if 'promoteImageOnly' is false (because in that case deployment doesn't apply
        // for this component)
        if (deployServices && !boxChecked && !promoteImageOnly) {
            servicesToSkip.add(data['templateName'])
        }
    }

    return [
        envConfig: envs[params.ENV],
        imagesToCopy: imagesToCopy,
        servicesToSkip: servicesToSkip,
        deployServices: envs[params.ENV]['deployServices']
    ]
}


def runDeploy(parsed) {
    // Run the actual deploy after the parameters have been parsed
    def imagesToCopy = parsed['imagesToCopy']
    def servicesToSkip = parsed['servicesToSkip']
    def envConfig = parsed['envConfig']
    def deployServices = parsed['deployServices']

    echo "imagesToCopy:   ${imagesToCopy}, servicesToSkip: ${servicesToSkip}"
    echo "envConfig:      ${envConfig}"

    currentBuild.description = "env: ${envConfig['env']}"

    openShiftUtils.withNode(image: "jenkins-deploy-slave:latest") {
        pipelineUtils.stageIf(imagesToCopy, 'Copy images') {
            // 'imagesToCopy' is a list of Maps, generate the args we need for promoteImages
            def srcImages = []
            def dstImages = []
            parsed['imagesToCopy'].each { srcImage, dstImage ->
                srcImages.add(srcImage)
                dstImages.add(dstImage)
            }

            deployUtils.promoteImages(
                srcImages: srcImages,
                dstImages: dstImages,
                dstProject: envConfig['project'],
                dstSaUsername: envConfig['saUsername'],
                dstSaTokenCredentialsId: envConfig['saTokenCredentialsId'],
                dstCluster: envConfig['cluster']
            )
        }

        stage('Login as deployer account') {
            withCredentials([
                string(credentialsId: envConfig['saTokenCredentialsId'], variable: 'TOKEN')
            ]) {
                sh "oc login https://${envConfig['cluster']} --token=${TOKEN}"
            }

            sh "oc project ${envConfig['project']}"
        }

        pipelineUtils.stageIf(deployServices, 'Run e2e-deploy') {
            deployUtils.deployServiceSet(
                serviceSet: envConfig['serviceSet'],
                skip: servicesToSkip,
                env: envConfig['env'],
                project: envConfig['project'],
                secretsSrcProject: envConfig['secretsSrcProject'],
            )
        }
    }
}


def call(p = [:]) {
    // Create a deployment pipeline job given an environment and service config
    def envs = p['environments']
    def svcs = p['services']
    def extraParams = p['extraParams'] ?: []
    def slackChannel = p.get('slackChannel')
    def slackUrl = p.get('slackUrl')

    properties([parameters(getJobParams(envs, svcs) + extraParams)])
    parsed = parseParams(envs, svcs)

    // Exit the job if the "reload" box was checked
    if (params.RELOAD) {
        echo "Job is only reloading"
        currentBuild.description = "reload"
        return
    }

    // Exit the job if this env should be ignored
    if (parsed['envConfig'].get('disabled')) {
        echo "Env has 'disabled' set"
        return
    }

    // For build #1, only load the pipeline and exit
    // This is so the next time the job is built, "Build with parameters" will be available
    if (env.BUILD_NUMBER.toString() == "1") {
        echo "Initial run, loaded pipeline job and now exiting."
        currentBuild.description = "loaded params"
        return
    }

    def envName = parsed['envConfig']['env']
    def verboseNotifications = parsed['envConfig'].get('verboseNotifications', true)

    if (verboseNotifications) {
        slackUtils.sendMsg(
            slackChannel: slackChannel,
            slackUrl: slackUrl,
            result: "info",
            msg: "started for env *${envName}*"
        )
    }

    try {
        runDeploy(parsed)
    } catch (err) {
        slackUtils.sendMsg(
            slackChannel: slackChannel,
            slackUrl: slackUrl,
            result: "failure",
            msg: "failed for env *${envName}*",
        )
        throw err
    }

    if (verboseNotifications) {
        slackUtils.sendMsg(
            slackChannel: slackChannel,
            slackUrl: slackUrl,
            result: "success",
            msg: "successful for env *${envName}*"
        )
    }
}
