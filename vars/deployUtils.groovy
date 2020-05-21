/**
 * Various helpers that relate to using ocdeployer/skopeo when deploying insights apps as well as
 * analyzing changes to the e2e-deploy template repo and generating deploy jobs based on those
 * detected changes
 */

import groovy.transform.Field


// The env files that are processed whenever a template change is detected
@Field def defaultEnvs = ["ci", "qa", "prod"]

// A const which represents 'all templates should be processed'
@Field def allTemplates = "__ALL__"

// Map of service set name -> Jenkins location of deploy job for that service set
@Field def deployJobs = [
    advisor: "/ops/deployAdvisor",
    approval: "/ops/deployApproval",
    "automation-analytics": "/ops/deployAutomationAnalytics",
    buildfactory: "/ops/deployBuildfactory",
    catalog: "/ops/deployCatalog",
    "ccx-data-pipeline": "/ops/deployCCX",
    cloudigrade: "/ops/deployCloudigrade",
    compliance: "/ops/deployCompliance",
    policies: "/ops/deployPolicies",
    drift: "/ops/deployDrift",
    hccm: "/ops/deployHccm",
    ingress: "/ops/deployIngress",
    inventory: "/ops/deployInventory",
    marketplace: "/ops/deployMarketplace",
    mnm: "/ops/deployMnm",
    patchman: "/ops/deployPatchman",
    "payload-tracker": "/ops/deployPayloadTracker",
    rbac: "/ops/deployRbac",
    sources: "/ops/deploySources",
    subscriptions: "/ops/deploySubscriptions",
    "system-baseline": "/ops/deployBaseline",
    "topological-inventory": "/ops/deployTopologicalInventory",
    vmaas: "/ops/deployVmaas",
    vulnerability: "/ops/deployVulnerability",
]


def getServiceDeployJobs() {
    return deployJobs.findAll({ !it.key.equals("buildfactory") })
}


private def analyzeEnvFile(String envFile, Map changeInfo, String serviceSet, String specificEnv) {
    def msg = "getChangeInfo: envFile is: ${envFile}"
    msg += serviceSet ? " in service set ${serviceSet}" : ""
    echo(msg)

    if (envFile.endsWith(".yaml") || envFile.endsWith(".yml")) {
        def envName = envFile.split("\\.")[0]
        echo "getChangeInfo: env file's name is: ${envName}"

        // if we are only analyzing a specific env, ignore other changed env files
        if (!specificEnv || envName == specificEnv) {
            changeInfo['envs'].add(envName)
            changeInfo['envsForDiff'].add(envName)

            if (!serviceSet) {
                // if a root env yaml was changed, process all templates using that env file
                // NOTE: this assumes that 'buildfactory' templates don't use any env files
                changeInfo['templates'].add(allTemplates)
            } else {
                // otherwise if it was a service set env yaml, process just that set's templates
                changeInfo['templates'].add(serviceSet)
            }
        } else {
            echo(
                "getChangeInfo: ignoring changes for ${envFile} -- only analyzing " +
                "changes for env ${specificEnv}"
            )
        }
    }
}


private def analyzeTemplateDir(
    String serviceSet, String dirName, Map changeInfo, Boolean ignoreRoot
) {
    echo "getChangeInfo: service set is ${serviceSet}"

    /*
    // If root _cfg.yml was edited, process all templates, unless we are ignoring
    // changes to the root cfg
    if (serviceSet.startsWith("_cfg") && !ignoreRoot) changeInfo[dirName].add(allTemplates)
    // Otherwise process only this service set
    else changeInfo[dirName].add(serviceSet)
    */

    // until there's a need ... always ignore root _cfg change for now to avoid mass deploys
    if (!serviceSet.startsWith("_cfg")) changeInfo[dirName].add(serviceSet)

    // Run diff using all default env files any time templates change
    changeInfo['envsForDiff'].addAll(defaultEnvs)
}


def getChangeInfo(parameters = [:]) {
    /**
     * Analyze the files changed in the e2e-deploy template folder and return data on what has
     * changed so we can determine what needs to be processed or deployed
     *
     * @param filesChanged list of files that have changed in the e2e-deploy dir
     * @param envName specific environment to get change info for (e.g. ci, prod, -- default: all)
     * @param ignoreRoot do not process all templates if the root _cfg.yaml changed (default: false)
     *
     * @return map with the following keys:
     * 'buildfactory' -- list of service sets that changed in 'buildfactory' dir
     * 'templates' -- list of service sets that changed in 'templates' dir
     * 'envFiles' -- list of files that changed in 'env' dir
     * 'envFilesForDiff' -- list of files that should be used in 'ocdeployer process'
                            when analyzing changes to the e2e-deploy repo
     */
    def filesChanged = parameters['filesChanged']
    def envName = parameters.get('env')
    def ignoreRoot = parameters.get('ignoreRoot')

    def changeInfo = [buildfactory: [], templates: [], envs: [], envsForDiff: []]

    echo "getChangeInfo: analyzing ${filesChanged}"

    for (String path : filesChanged) {
        def splitPath = path.split('/')

        echo "getChangeInfo: analyzing file: ${path}"

        def dir = splitPath[0]
        echo "getChangeInfo: dir is: ${dir}"

        if (dir == "env") {
            def envFileName = splitPath[1]
            analyzeEnvFile(envFileName, changeInfo, null, envName)
        }
        else if (dir == "templates" || dir == "buildfactory") {
            def serviceSet = splitPath[1]

            // Something in this service set changed, so process it...
            analyzeTemplateDir(serviceSet, dir, changeInfo, ignoreRoot)

            // Check if an env file changed at 'dir/serviceSet/env/file.yml'
            if (splitPath.size() >= 4 && splitPath[2] == "env") {
                echo "Analyzing service set env file"
                envFileName = splitPath[3]
                analyzeEnvFile(envFileName, changeInfo, serviceSet, envName)
            }
        }
    }

    // De-dupe the lists
    changeInfo['envs'] = changeInfo['envs'].toSet()
    changeInfo['envsForDiff'] = changeInfo['envsForDiff'].toSet()
    changeInfo['templates'] = changeInfo['templates'].toSet()
    changeInfo['buildfactory'] = changeInfo['buildfactory'].toSet()

    // If we are processing all templates for a template dir, don't bother indicating specific sets
    if (changeInfo['templates'].contains(allTemplates)) changeInfo['templates'] = [allTemplates]
    if (changeInfo['buildfactory'].contains(allTemplates)) {
        changeInfo['buildfactory'] = [allTemplates]
    }

    return changeInfo
}


def createDeployAllChangeInfo(String env) {
    // Return a changeinfo map that will cause all apps to be marked for deploy in the given 'env'
    return [
        'buildfactory': [allTemplates],
        'templates': [allTemplates],
        'envs': [env],
        'envsForDiff': [env]
    ]
}


private def getRemoteTask(buildJob, jobParameters, remoteCredentials, remoteHostname) {
    // Return a closure for running a build job on a remote jenkins master

    // Translate the params into a string assuming it is a list of Maps e.g.:
    // [[$class: StringParameterValue, name: "name", value: "value"]]
    def paramsString = ""
    for (Map p : jobParameters) {
        paramsString = paramsString + "\n${p['name']}=${p['value'].toString()}"
    }

    // Translate the build job name into its URL format
    def fullUrlPath = buildJob.replace('/', '/job/')

    closure = {
        withCredentials([
            string(credentialsId: remoteCredentials, variable: "TOKEN"),
            string(credentialsId: remoteHostname, variable: "REMOTE_HOSTNAME")
        ]) {
            triggerRemoteJob(
                job: "https://${REMOTE_HOSTNAME}${fullUrlPath}",
                parameters: paramsString,
                auth: BearerTokenAuth(token: TOKEN)
            )
        }
    }

    return closure
}


def getDeployTask(parameters = [:]) {
    /**
     * Return a closure for running a build job that deploys a service set into the specified env
     *
     * @param serviceSet Name of service set to deploy
     * @param env Name of env to deploy to
     * @param remote Whether or not the job is built on a remote jenkins master (default: false)
     * @param remoteCredentials Jenkins secret name for a remote Jenkins API token
        (default: 'remoteJenkinsApiToken')
     * @param remoteHostname Jenkins secret name for a remote Jenkins host
        (default: 'remoteJenkinsHostname')
     * @param jobParameters list of parameters to pass to the build job
        (default: single string param with 'ENV' set to env)
     * @return Closure
     */
    def setToDeploy = parameters['serviceSet']
    def envName = parameters['env']
    def remote = parameters.get('remote', false)
    def remoteCredentials = parameters.get('remoteCredentials', "remoteJenkinsApiToken")
    def remoteHostname = parameters.get('remoteHostname', "remoteJenkinsHostname")
    def jobParameters = parameters.get(
        'jobParameters',
        [[$class: 'StringParameterValue', name: 'ENV', value: envName]]
    )
    def extraJobParameters = parameters.get('extraJobParameters', [])
    jobParameters.addAll(extraJobParameters)

    def closure
    def buildJob = deployJobs.get(setToDeploy)
    if (!buildJob) closure = { echo "Unable to find entry in deployJobs for service set ${setToDeploy}" }
    else if (remote) closure = getRemoteTask(buildJob, jobParameters, remoteCredentials, remoteHostname)
    else closure = { build job: buildJob, parameters: jobParameters }

    echo(
        "getDeployTask(): service set \'${setToDeploy}\' will trigger job \'${buildJob}\' " +
        "with params \'${jobParameters.toString()}\' -- task is ${closure.toString()}"
    )
    return closure
}


def createParallelTasks(parameters = [:]) {
    /**
     * Generate a map to be used for concurrent deploys utilizing 'parallel()' where
     *      key=service set name
     *      value=closure containing a build job
     *
     * @param serviceSets List of service set names
     * @param env Name of env to deploy to
     * @param remote Whether or not the jobs are built on a remote jenkins master (default: false)
     * @param remoteCredentials Jenkins secret name for a remote Jenkins API token
        (default: 'remoteJenkinsApiToken')
     * @param remoteHostname Jenkins secret name for a remote Jenkins host
        (default: 'remoteJenkinsHostname')
     * @return Map
     */
    def serviceSets = parameters['serviceSets']
    def envName = parameters['env']
    def remote = parameters['remote']
    def remoteCredentials = parameters.get('remoteCredentials', "remoteJenkinsApiToken")
    def remoteHostname = parameters.get('remoteHostname', "remoteJenkinsHostname")

    def tasks = [:]
    for (String set : serviceSets) {
        // re-define the loop variable, see:
        // http://blog.freeside.co/2013/03/29/groovy-gotcha-for-loops-and-closure-scope/
        def thisSet = set
        tasks[thisSet] = getDeployTask(
            serviceSet: thisSet, env: envName, remote: remote,
            remoteCredentials: remoteCredentials, remoteHostname: remoteHostname
        )
    }

    return tasks
}


private def createBuildfactoryTask(
    changeInfo, e2eDeployDir, remote, remoteCredentials, remoteHostname
) {
    // Get list of buildfactory service sets to deploy
    def serviceSets
    if (changeInfo['buildfactory'].contains(allTemplates)) {
        serviceSets = getServiceDeployJobs().keySet()
    } else {
        serviceSets = changeInfo['buildfactory']
    }

    // Generate the params for the build job
    def buildParams = []
    for (String serviceSet : serviceSets) {
        if (fileExists("${e2eDeployDir}/buildfactory/${serviceSet}")) {
            buildParams.add([
                $class: 'BooleanParameterValue',
                name: "deploy_${serviceSet}_builds",
                value: true
            ])
        }
    }

    return getDeployTask(
        serviceSet: "buildfactory",
        jobParameters: buildParams,
        remote: remote,
        remoteCredentials: remoteCredentials,
        remoteHostname: remoteHostname
    )
}


private def createTemplateTasks(
    envName, changeInfo, e2eDeployDir, remote, remoteCredentials, remoteHostname
) {
    // If all templates are impacted by a change, re-deploy all services
    def allTemplatesChanged = changeInfo['templates'].contains(allTemplates)
    if (allTemplatesChanged) {
        return createParallelTasks(
            serviceSets: getServiceDeployJobs().keySet(),
            env: envName,
            remote: remote,
            remoteCredentials: remoteCredentials,
            remoteHostname: remoteHostname
        )
    }

    // Otherwise run deploy jobs for only the service sets that had changes
    def serviceSets = []
    for (String serviceSet : changeInfo['templates']) {
        if (deployJobs.containsKey(serviceSet)) {
            if (fileExists("${e2eDeployDir}/templates/${serviceSet}")) {
                serviceSets.add(serviceSet)
            }
        }
    }
    return createParallelTasks(
        serviceSets: serviceSets,
        env: envName,
        remote: remote,
        remoteCredentials: remoteCredentials,
        remoteHostname: remoteHostname
    )
}


def getDeployTasksFromChangeInfo(parameters = [:]) {
    /**
     * Given change info (as returned by getChangeInfo), an env name, and the the path to the
     * current e2e-deploy commit, return a list of deploy jobs that to be run as parallel tasks
     *
     * A service set is only deployed if:
        1. it is listed in deployJobs
        2. its dir still exists in e2e-deploy
            (if not this means the dir was removed in the latest e2e-deploy commits)
     *
     * @param changeInfo Map returned by deployHelpers.getChangeInfo
     * @param envName String for destination env name (e.g. "ci", "qa", "prod")
     * @param remote Whether or not the jobs are built on a remote jenkins master (default: false)
     * @param remoteCredentials Jenkins secret name for a remote Jenkins API token
        (default: 'remoteJenkinsApiToken')
     * @param remoteHostname Jenkins secret name for a remote Jenkins host
        (default: 'remoteJenkinsHostname')
     * @param e2eDeployDir path to current e2e-deploy commit, by default we assume it
        has been checked out into $WORKSPACE
     * @return Map
     */
    def changeInfo = parameters['changeInfo']
    def envName = parameters['env']
    def remote = parameters['remote']
    def remoteCredentials = parameters.get('remoteCredentials', "remoteJenkinsApiToken")
    def remoteHostname = parameters.get('remoteHostname', "remoteJenkinsHostname")
    def e2eDeployDir = parameters.get('e2eDeployDir', env.WORKSPACE)

    def parallelTasks = [:]

    // If env is CI or QA and a service set in buildfactory was updated, run the buildfactory
    // deploy job with the proper service sets selected
    if ((envName.equals("ci") || envName.equals("qa")) && changeInfo['buildfactory']) {
        parallelTasks["buildfactory"] = createBuildfactoryTask(
            changeInfo, e2eDeployDir, remote, remoteCredentials, remoteHostname
        )
    }

    parallelTasks = parallelTasks + createTemplateTasks(
        envName, changeInfo, e2eDeployDir, remote, remoteCredentials, remoteHostname
    )

    return parallelTasks
}


def installE2EDeploy() {
    gitUtils.checkOutRepo(
        targetDir: pipelineVars.e2eDeployDir,
        repoUrl: pipelineVars.e2eDeployRepoSsh,
        credentialsId: pipelineVars.gitSshCreds
    )
    dir(pipelineVars.e2eDeployDir) {
        sh "pip install -r requirements.txt"
    }
}


def deployServiceSet(parameters = [:]) {
    /**
     * Deploy an e2e-deploy service set using ocdeployer
     */
    def serviceSet = parameters['serviceSet']
    def env = parameters.get('env')
    def project = parameters['project']
    def secretsSrcProject = parameters.get('secretsSrcProject')
    def templateDir = parameters.get('templateDir', "templates")
    def skip = parameters.get('skip')
    def pipInstall = parameters.get('pipInstall', true)
    def watch = parameters.get('watch', true)

    if (pipInstall) installE2EDeploy()
    dir(pipelineVars.e2eDeployDir) {
        def watchArg = watch ? " -w " : " "
        def envArg = env ? " -e ${env} " : " "
        def secretsArg = secretsSrcProject ? " --secrets-src-project ${secretsSrcProject}" : " "
        def cmd = (
            "ocdeployer deploy${watchArg}-f -t ${templateDir} " +
            "-s ${serviceSet}${envArg}${project}${secretsArg}"
        )
        if (skip) cmd = "${cmd} --skip ${skip.join(",")}"
        sh cmd
    }
}


def skopeoCopy(parameters = [:]) {
    def srcUri = parameters['srcUri']
    def dstUri = parameters['dstUri']
    def srcUser = parameters['srcUser']
    def srcTokenId = parameters['srcTokenId']
    def dstUser = parameters['dstUser']
    def dstTokenId = parameters['dstTokenId']

    withCredentials(
        [
            string(credentialsId: srcTokenId, variable: 'SRC_TOKEN'),
            string(credentialsId: dstTokenId, variable: 'DST_TOKEN')
        ]
    ) {
        sh(
            "skopeo copy --src-creds=\"${srcUser}:${SRC_TOKEN}\" " +
            "--dest-creds=\"${dstUser}:${DST_TOKEN}\" ${srcUri} ${dstUri}"
        )
    }
}


private def promoteToCluster(
    srcImage, dstImage, srcCluster, srcProject, dstCluster, dstProject, srcSaUsername,
    srcSaTokenCredentialsId, dstSaUsername, dstSaTokenCredentialsId
) {
    /* Promotes images from a src OpenShift cluster to a dst OpenShift cluster */
    def srcRegistry = srcCluster.replace("api", "registry")
    def dstRegistry = dstCluster.replace("api", "registry")
    def imageFormat = "docker://%s/%s/%s"

    def srcImageUri = String.format(imageFormat, srcRegistry, srcProject, srcImage)
    def dstImageUri = String.format(imageFormat, dstRegistry, dstProject, dstImage)
    skopeoCopy(
        srcUri: srcImageUri,
        dstUri: dstImageUri,
        srcUser: srcSaUsername,
        srcTokenId: srcSaTokenCredentialsId,
        dstUser: dstSaUsername,
        dstTokenId: dstSaTokenCredentialsId,
    )
}


private def promoteToQuay(
    srcImage, dstImage, srcCluster, srcProject, srcSaUsername, srcSaTokenCredentialsId, dstQuayUser,
    dstQuayTokenId
) {
    /* Promotes images from a src OpenShift cluster to quay */
    def srcRegistry = srcCluster.replace("api", "registry")
    def imageFormat = "docker://%s/%s/%s"
    def srcImageUri = String.format(imageFormat, srcRegistry, srcProject, srcImage)
    def dstImageUri = "docker://" + dstImage

    skopeoCopy(
        srcUri: srcImageUri,
        dstUri: dstImageUri,
        srcUser: srcSaUsername,
        srcTokenId: srcSaTokenCredentialsId,
        dstUser: dstQuayUser,
        dstTokenId: dstQuayTokenId,
    )
}


def promoteImages(parameters = [:]) {
    /**
     * Use skopeo to copy images from one OpenShift registry to another, or to quay
     *
     * If the dstImage is a quay URI, we will promote the image to quay.
     * If it is not, we promote it to the OpenShift cluster specified by 'dstCluster'.
     *
     * @param srcImages REQUIRED images to copy from, e.g. ["myimage2:latest", "myimage3:other_tag"]
     * @param dstImages images to copy to, e.g. ["myimage2:prod", "myimage3:prod"]
        (default: the same as srcImages)
     * @param srcProject namespace to copy from (default: buildfactory)
     * @param dstProject REQUIRED namespace to copy to
     * @param srcCluster cluster to copy from (default: pipelineVars.devCluster)
     * @param dstCluster cluster to copy to (default: pipelineVars.prodCluster)
     * @param srcSaUsername service account user for source cluster
        (default: 'jenkins-deployer')
     * @param srcSaTokenCredentialsId Jenkins secret name for source service account token
        (default: 'buildfactoryDeployerToken')
     * @param dstSaUsername service account user for destination cluster
        (default: 'jenkins-deployer')
     * @param dstSaTokenCredentialsId REQUIRED Jenkins secret name for dst service account token
     * @param dstQuayUser username for quay (default: pipelineVars.quayUser)
     * @param dstQuayTokenId Jenkins secret name for quay token
        (default: pipelineVars.quayPushCredentialsId)
     *
     * @return
     */

    def srcImages = parameters['srcImages']
    def dstImages = parameters.get('dstImages')
    def srcProject = parameters.get('srcProject', "buildfactory")
    def srcCluster = parameters.get('srcCluster', pipelineVars.devCluster)
    def dstProject = parameters.get('dstProject')
    def dstCluster = parameters.get('dstCluster', pipelineVars.prodCluster)
    def srcSaUsername = parameters.get('srcSaUsername', "jenkins-deployer")
    def srcSaTokenCredentialsId = parameters.get(
        'srcSaTokenCredentialsId', "buildfactoryDeployerToken"
    )
    def dstSaUsername = parameters.get('dstSaUsername', "jenkins-deployer")
    def dstSaTokenCredentialsId = parameters.get('dstSaTokenCredentialsId')
    def dstQuayUser = parameters.get("dstQuayUser", pipelineVars.quayUser)
    def dstQuayTokenId = parameters.get("dstQuayTokenId", pipelineVars.quayPushCredentialsId)

    if (!dstImages) dstImages = srcImages
    if (srcImages.size() != dstImages.size()) error("srcImages and dstImages must be the same size")

    srcImages.eachWithIndex { srcImage, i ->
        def dstImage = dstImages[i]
        if (dstImage.startsWith(pipelineVars.quayBaseUri)) {
            promoteToQuay(
                srcImage, dstImage, srcCluster, srcProject, srcSaUsername, srcSaTokenCredentialsId,
                dstQuayUser, dstQuayTokenId
            )
        } else {
            if (!dstProject || !dstSaTokenCredentialsId) {
                error(
                    "'dstProject'/'dstSaTokenCredentialsId' must be defined for cluster image copy"
                )
            }
            promoteToCluster(
                srcImage, dstImage, srcCluster, srcProject, dstCluster, dstProject, srcSaUsername,
                srcSaTokenCredentialsId, dstSaUsername, dstSaTokenCredentialsId
            )
        }
    }
}
