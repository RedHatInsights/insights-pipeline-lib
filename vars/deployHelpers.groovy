import groovy.transform.Field


// The env files that are processed whenever a template change is detected
@Field defaultEnvFiles = ["ci.yml", "qa.yml", "dev.yml", "prod.yml"]

// A const which represents 'all templates should be processed'
@Field allTemplates = "__ALL__"

// Map of service set name -> Jenkins location of deploy job for that service set
@Field deployJobs = [
    buildfactory: "/ops/deployBuildfactory",
    advisor: "/ops/deployAdvisor",
    approval: "/ops/deployApproval",
    catalog: "/ops/deployCatalog",
    platform: "/ops/deployPlatform",
    sources: "/ops/deploySources",
    "topological-inventory": "/ops/deployTopologicalInventory",
    compliance: "/ops/deployCompliance",
    "system-baseline": "/ops/deployBaseline",
    vulnerability: "/ops/deployVulnerability",
    rbac: "/ops/deployRbac",
    hccm: "/ops/deployHccm",
    subscriptions: "/ops/deploySubscriptions"
]


def getServiceDeployJobs() {
    return deployJobs.findAll({ !it.key.equals("buildfactory") })
}


def getChangeInfo(parameters = [:]) {
    // Analyze the files changed in the e2e-deploy template folder and return data on what has changed
    // so we can determine what needs to be processed or deployed

    // list of files that have changed in the repo (obtained via getFilesChanged)
    def filesChanged = parameters['filesChanged']
    // get change info that applies to a specific environment (default: all environments)
    def envName = parameters.get('env')
    // do not process all templates if the root _cfg.yaml was changed (default: false)
    def ignoreRoot = parameters.get('ignoreRoot')

    def changeInfo = [buildfactory: [], templates: [], envFiles: [], envFilesForDiff: []]

    for (String l : filesChanged) {
        def dir = l.split('/')[0]
        def args = null

        if (dir == "env") {
            // if an env yaml was changed, process all templates using that env file
            def envFile = l.split('/')[1]

            if (envFile.endsWith(".yaml") || envFile.endsWith(".yml")) {
                def envFileSplit = envFile.split('.')
                // if we are only analyzing a specific env, ignore other changed env files
                if (!envName || envFileSplit[0].equals(envName)) {
                    changeInfo['templates'].add(allTemplates)
                    changeInfo['envFiles'].add(envFile)
                    changeInfo['envFilesForDiff'].add(envFile)
                } else {
                    echo "Ignoring changes for ${envFile} -- only analyzing changes for env ${envName}"
                }
            }
        }
        else if (dir == "templates") {
            def serviceSet = l.split('/')[1]
            // If root _cfg.yml was edited, and we are not ignoring changes to the root cfg, process all templates
            if (serviceSet.startsWith("_cfg") && !ignoreRoot) changeInfo[dir].add(allTemplates)
            // Otherwise process only this service set
            else changeInfo[dir].add(serviceSet)
            // Process all default env files any time templates change
            changeInfo['envFilesForDiff'].addAll(defaultEnvFiles)
        }
        else if (dir == "buildfactory") {
            def serviceSet = l.split('/')[1]
            if (!serviceSet.startsWith("_cfg")) changeInfo[dir].add(serviceSet)
        }
    }
    // De-dupe the lists
    changeInfo['envFiles'] = changeInfo['envFiles'].toSet()
    changeInfo['envFilesForDiff'] = changeInfo['envFilesForDiff'].toSet()
    changeInfo['templates'] = changeInfo['templates'].toSet()
    changeInfo['buildfactory'] = changeInfo['buildfactory'].toSet()

    // If we are processing all templates for a template dir, then don't bother indicating specific sets  
    if (changeInfo['templates'].contains(allTemplates)) changeInfo['templates'] = [allTemplates]
    if (changeInfo['buildfactory'].contains(allTemplates)) changeInfo['buildfactory'] = [allTemplates]

    return changeInfo
}


private def getRemoteTask(buildJob, jobParameters, remoteCredentials, remoteHostname) {
    // Translate the params into a string, assume it is a list of Maps e.g. [[$class: StringParameterValue, name: "name", value: "value"]]
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
    // Given a service set and an 'env', return a single build job that will run as a parallel task
    def setToDeploy = parameters['serviceSet']
    def envName = parameters['env']

    // Boolean parameter used to indicate the build job is triggered on remote jenkins
    def remote = parameters['remote']
    // Name of secret containing username and password credentials
    def remoteCredentials = parameters.get('remoteCredentials', "remoteJenkinsApiToken")
    // Name of secret containing the remote Jenkins hostname
    def remoteHostname = parameters.get('remoteHostname', "remoteJenkinsHostname")
    // Parameters to pass on to the job
    def jobParameters = parameters.get('jobParameters', [[$class: 'StringParameterValue', name: 'ENV', value: envName]])

    def buildJob = deployJobs[setToDeploy]
    def closure
    if (remote) closure = getRemoteTask(buildJob, jobParameters, remoteCredentials, remoteHostname)
    else closure = { build job: buildJob, parameters: jobParameters }

    echo "getDeployTask(): service set \'${setToDeploy}\' will trigger job \'${buildJob}\' with params \'${jobParameters.toString()}\' -- task is ${closure.toString()}"
    return closure
}


def createParallelTasks(parameters = [:]) {
    // Generate a map to be used with 'parallel()' where key=service set name, value=closure containing a build job
    def tasks = [:]

    // Since looping while returning closures in groovy is a little wacky, we handle that in this method
    def serviceSets = parameters['serviceSets']
    def envName = parameters['env']

    // Boolean parameter used to indicate the build job is triggered on remote jenkins
    def remote = parameters['remote']
    // Name of secret containing username and password credentials
    def remoteCredentials = parameters.get('remoteCredentials', "remoteJenkinsApiToken")
    // Name of secret containing the remote Jenkins hostname
    def remoteHostname = parameters.get('remoteHostname', "remoteJenkinsHostname")

    for (String set : serviceSets) {
        def thisSet = set  // re-define the loop variable, see http://blog.freeside.co/2013/03/29/groovy-gotcha-for-loops-and-closure-scope/
        tasks[thisSet] = getDeployTask(
            serviceSet: thisSet, env: envName, remote: remote, remoteCredentials: remoteCredentials, remoteHostname: remoteHostname
        )
    }

    return tasks
}


def getDeployTasksFromChangeInfo(parameters = [:]) {
    // By analyzing changeInfo and given an 'env', return a list of build jobs that will be run as parallel tasks

    // Deploy repo change info as obtained by deployHelpers.getChangeInfo
    def changeInfo = parameters.get('changeInfo')
    // The destination env "ci, qa, prod"
    def envName = parameters.get('env')

    // Boolean parameter used to indicate the build job is triggered on remote jenkins
    def remote = parameters['remote']
    // Name of secret containing username and password credentials
    def remoteCredentials = parameters.get('remoteCredentials', "remoteJenkinsApiToken")
    // Name of secret containing the remote Jenkins hostname
    def remoteHostname = parameters.get('remoteHostname', "remoteJenkinsHostname")

    // e2e-deploy directory to analyze, by default we assume the repo has been checked out into $WORKSPACE
    def e2eDeployDir = parameters.get('e2eDeployDir', env.WORKSPACE)

    def parallelTasks = [:]

    // If checking for changes for CI or QA and a service set in buildfactory was updated, re-deploy it
    if ((envName.equals("ci") || envName.equals("qa")) && changeInfo['buildfactory']) {
        if (changeInfo['buildfactory'].contains(allTemplates)) changeInfo['buildfactory'] = getServiceDeployJobs().keySet()

        def buildParams = []
        for (String serviceSet : changeInfo['buildfactory']) {
            // Only deploy a service set if:
            //   * it is listed in deployJobs
            //   * its dir still exists in e2e-deploy -- if not this means the dir was removed in the latest e2e-deploy commits
            if (fileExists("${e2eDeployDir}/buildfactory/${serviceSet}")) {
                buildParams.add([$class: 'BooleanParameterValue', name: "deploy_${serviceSet}_builds", value: true])
            }
        }

        parallelTasks["buildfactory"] = getDeployTask(
            serviceSet: "buildfactory", jobParameters: buildParams, remote: remote, remoteCredentials: remoteCredentials, remoteHostname: remoteHostname
        )
    }

    // If the env yml was updated, or all templates are impacted by a change, re-deploy all services
    // TODO: in future parse the env yml to see if only specific portions changed?

    if (changeInfo['templates'].contains(allTemplates) || changeInfo['envFiles'].contains("${env}.yml")) {
        parallelTasks = parallelTasks + createParallelTasks(
            serviceSets: getServiceDeployJobs().keySet(), env: envName, remote: remote, remoteCredentials: remoteCredentials, remoteHostname: remoteHostname
        )
    // Otherwise run deploy job for only the service sets that had changes
    } else {
        def serviceSets = []
        for (String serviceSet : changeInfo['templates']) {
            // Only deploy a service set if:
            //   * it is listed in deployJobs
            //   * its dir still exists in e2e-deploy -- if not this means the dir was removed in the latest e2e-deploy commits
            if (deployJobs.containsKey(serviceSet) && fileExists("${e2eDeployDir}/templates/${serviceSet}")) {
                serviceSets.add(serviceSet)
            }
        }
        parallelTasks = parallelTasks + createParallelTasks(
            serviceSets: serviceSets, env: envName, remote: remote, remoteCredentials: remoteCredentials, remoteHostname: remoteHostname
        )
    }

    // Return the map of parallelTasks
    return parallelTasks
}
