/*
 * Runs execIQETests and wraps it with a slack notifier that behaves in the following way:
 *
 * 1. sends a slack msg only when test results change from fail->pass or pass->fail
 * 2. for failed tests, a callback function can be used to generate your own custom error msg
 * 3. ignores jobs that have a "reload" status
 * 4. sends a slack msg to a separate channel when an unhandled error occurs (e.g. job errors
        unrelated to the test itself failing)
 */


def getCurrentMinusBuilds(number) {
    int count = 0
    currentList = []
    
    // otherwise our list will include currentMinusBuilds - 1
    while(count != number) {
        if (currentList) {
            currentMinusX = pipelineUtils.getLastRealBuild(currentList.last())
        } else {
            currentMinusX = pipelineUtils.getLastRealBuild(currentBuild)
        }

        // Avoid adding nulls to our list
        if (currentMinusX) {
            echo "Found currentMinus${count + 1} non-RELOAD/non-ERROR build: ${currentMinusX.getDisplayName()}"
            currentList.add(currentMinusX);
        }
        count++;
    }
    return currentList
}

def checkResolved(buildList, currentMinusBuilds) {
    int failures = 0

    for(build in buildList) { 
        if (build.getResult().toString() != "SUCCESS") {
            echo "build ${build.getDisplayName()}'s result was a ${build.getResult().toString()}"
            failures++;
        }
    }

    if (buildList.last().getResult().toString() != "SUCCESS" && failures == 1 && buildList.size() == currentMinusBuilds) {
        // We went from a failure and have had a consistent run of successes since
        return true
    } else {
        // We have had more than just the initial failure we were hoping to resolve
        return false
    }
}

def call(args = [:]) {
    def defaultSlackMsgCallback = { return "test failed" }

    // arguments to pass to execIQETests
    def appConfigs = args['appConfigs']
    def envs = args['envs']
    def options = args.get('options', [:])
    def defaultMarker = args.get('defaultMarker', pipelineVars.defaultMarker)
    def defaultFilter = args.get('defaultFilter')
    def requirements = args.get('requirements')
    def requirementsPriority = args.get('requirementsPriority')
    def testImportance = args.get('testImportance')
    def extraJobProperties = args.get('extraJobProperties', [])
    def lockName = args.get('lockName')

    // arguments specific to slack notifier

    // OPTIONAL: if true, always send a failure notification
    def alwaysSendFailureNotification = args.get('alwaysSendFailureNotification', false)
    // OPTIONAL: slack integration URL
    def slackUrl = args.get('slackUrl', pipelineVars.slackDefaultUrl)
    // REQUIRED: where to report test failures
    def slackChannel = args['slackChannel']
    // REQUIRED: where to report unhandled errors when this job unexpectedly fails
    def errorSlackChannel = args['errorSlackChannel']
    // OPTIONAL: closure to call that generates detailed slack msg text when tests fail
    def slackMsgCallback = args.get('slackMsgCallback', defaultSlackMsgCallback)
    // OPTIONAL: slack team subdomain
    def slackTeamDomain = args.get('slackTeamDomain', pipelineVars.slackDefaultTeamDomain)
    // OPTIONAL: slack integration token
    def slackTokenCredentialId = args.get('slackTokenCredentialId', null)
    // OPTIONAL: how many past builds to use before we mark the run as a success
    def currentMinusBuilds = args.get('currentMinusBuilds', 2)

    builds = getCurrentMinusBuilds(currentMinusBuilds)
    runResolved = checkResolved(builds, currentMinusBuilds)

    def results
    try {
        results = execIQETests(
            appConfigs: appConfigs,
            envs: envs,
            options: options,
            defaultMarker: defaultMarker,
            defaultFilter: defaultFilter,
            requirements: requirements,
            requirementsPriority: requirementsPriority,
            testImportance: testImportance,
            extraJobProperties: extraJobProperties,
            lockName: lockName
        )

        // check that we actually got results
        if (!results) error("Found no test results, unexpected error must have occurred")

        if (results['failed']) {
            if (alwaysSendFailureNotification || (!builds.last() || builds.last().getResult().toString() == "SUCCESS")) {
                // result went from success -> failed
                // run script to collect request ID info and send the failure slack msg
                def slackMsg = slackMsgCallback()
                slackUtils.sendMsg(
                    slackChannel: slackChannel,
                    slackUrl: slackUrl,
                    slackTeamDomain: slackTeamDomain,
                    slackTokenCredentialId: slackTokenCredentialId,
                    msg: slackMsg.toString(),
                    result: "failure"
                )
            }
        }
        else if (runResolved) {
            // result went from failed -> success *currentMinusBuilds
            slackUtils.sendMsg(
                slackChannel: slackChannel,
                slackUrl: slackUrl,
                slackTeamDomain: slackTeamDomain,
                slackTokenCredentialId: slackTokenCredentialId,
                msg: "test resolved",
                result: "success"
            )
        }
    } catch (err) {
        if (currentBuild.description == "reload") return
        else {
            currentBuild.description = "error"
            slackUtils.sendMsg(
                slackChannel: errorSlackChannel,
                slackUrl: slackUrl,
                slackTeamDomain: slackTeamDomain,
                slackTokenCredentialId: slackTokenCredentialId,
                msg: "\nHit unhandled error:\n${err.getMessage()}",
                result: "failure"
            )
            throw err
        }
    }

    if (results['failed']) {
        error("Tests failed for apps: ${results['failed'].join(', ')}")
    }
}
