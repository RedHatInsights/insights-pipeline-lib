import groovy.transform.Field

// The env files that are processed whenever a template change is detected
@Field defaultEnvFiles = ["ci.yml", "qa.yml", "dev.yml", "prod.yml"]

// A const which represents 'all templates should be processed'
@Field allTemplates = "__ALL__"

// Jenkins location of build factory deployment job
@Field buildFactoryDeployJob = "/ops/deployBuildfactory"

// Map of service set name -> Jenkins location of deploy job for that serbvice set
@Field deployJobs = [
    advisor: "/ops/deployAdvisor",
    platform: "/ops/deployPlatform",
]


def getChangeInfo(parameters = [:]) {
    // Analyze the files changed in the e2e-deploy template folder and return data on what has changed
    // so we can determine what needs to be processed or deployed

    // list of files that have changed in the repo (obtained via getFilesChanged)
    def filesChanged = parameters['filesChanged']

    def changeInfo = [buildfactory: [], templates: [], envFiles: [], envFilesForDiff: []]

    for (String l : filesChanged) {
        def dir = l.split('/')[0]
        def args = null

        if (dir == "env") {
            // if an env yaml was changed, process all templates using that env file
            def envFile = l.split('/')[1]
            if (envFile.endsWith(".yaml") || envFile.endsWith(".yml")) {
                changeInfo['templates'].add(allTemplates)
                changeInfo['envFiles'].add(envFile)
                changeInfo['envFilesForDiff'].add(envFile)
            }
        }
        else if (dir == "buildfactory" || dir == "templates") {
            def serviceSet = l.split('/')[1]
            // If root _cfg.yml was edited, process all templates in this template dir
            if (serviceSet.startsWith("_cfg")) changeInfo[dir].add(allTemplates)
            // Otherwise process only this service set
            else changeInfo[dir].add(serviceSet)
            // Process all default env files any time templates change
            changeInfo['envFilesForDiff'].addAll(defaultEnvFiles)
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


def getDeployTask(parameters = [:]) {
    // Given a service set and an 'env', return a single build job that will run as a parallel task
    serviceSet = parameters['serviceSet']
    env = parameters['env']
    buildJob = deployJobs[serviceSet]
    closure = { build job: buildJob, parameters: [[$class: 'StringParameterValue', name: 'ENV', value: env]] }
    echo "getDeployTask(): service set \'${serviceSet}\' will trigger job \'${buildJob}\' with env \'${env}\' -- task is ${closure.toString()}"
    return closure
}


def createParallelTasks(parameters = [:]) {
    // Generate a map to be used with 'parallel()' where key=service set name, value=closure containing a build job

    // Since looping while returning closures in groovy is a little wacky, we handle that in this method
    serviceSets = parameters['sets']
    env = parameters['env']

    for (String set : serviceSets) {
        def thisSet = set  // re-define the loop variable, see http://blog.freeside.co/2013/03/29/groovy-gotcha-for-loops-and-closure-scope/
        parallelTasks[thisSet] = getDeployTask(serviceSet: thisSet, env: env)
    }

    return parallelTasks
}


def getDeployTasksFromChangeInfo(parameters = [:]) {
    // By analyzing changeInfo and given an 'env', return a list of build jobs that will be run as parallel tasks

    // Deploy repo change info as obtained by deployHelpers.getChangeInfo
    changeInfo = parameters.get('changeInfo')
    // The destination env "ci, qa, prod"
    env = parameters.get('env')

    // Now deploy services if their templates have changed...
    parallelTasks = [:]

    // If checking for changes for CI or QA and a service set in buildfactory was updated, re-deploy it
    if ((env.equals("ci") || env.equals("qa")) && changeInfo['buildfactory']) {
        // there shouldn't be a case at the moment where we're needing to deploy all sets of buildfactory at once
        parameters = []
        for (String serviceSet : changeInfo['buildfactory']) {
            parameters.add([$class: 'BooleanParameterValue', name: "deploy_${serviceSet}_builds", value: true])
        }
        build job: buildFactoryDeployJob, parameters: parameters
    }

    // If the env yml was updated, or all templates are impacted by a change, re-deploy all services
    // TODO: in future parse the env yml to see if only specific portions changed?
    if (changeInfo['templates'].contains(allTemplates) || changeInfo['envFiles'].contains("${env}.yml")) {
        parallelTasks = createParallelTasks(serviceSets: deployJobs.keySet(), env: env)
    // Otherwise run deploy job for only the service sets that had changes
    } else {
        def serviceSets = []
        for (String serviceSet : changeInfo['templates']) {
            if (deployJobs.containsKey(serviceSet)) {
                serviceSets.add(serviceSet)
            }
        }
        parallelTasks = createParallelTasks(serviceSets: serviceSets, env: env)
    }

    // Return the map of parallelTasks
    return parallelTasks
}