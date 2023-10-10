/*
 * Runs execIQETests and wraps it with a slack notifier that behaves in the following way:
 *
 * 1. sends a slack msg only when test results change from fail->pass or pass->fail
 * 2. for failed tests, a callback function can be used to generate your own custom error msg
 * 3. for passed tests, a callback function can be used to generate your own custom success msg
 * 4. ignores jobs that have a "reload" status
 * 5. sends a slack msg to a separate channel when an unhandled error occurs (e.g. job errors
        unrelated to the test itself failing)
 */


/*
 * Function is taking care of getting previous N builds
 *
 * @param reqNumOfBuilds Integer -- number of requested builds
 *
 * @return List -- list with the builds (youngest build first)
 */
def getPreviousBuilds(reqNumOfBuilds) {

    int count = 0
    prevBuildList = []

    while(count != reqNumOfBuilds) {
        if (prevBuildList) {
            currentBuildMinusX = prevBuildList.last().getPreviousBuild()
        } else {
            currentBuildMinusX = currentBuild.getPreviousBuild()
        }

        // Finish if no previous build
        if (!currentBuildMinusX) {
            break
        }
        echo "Found currentBuildMinus${count + 1} build: ${currentBuildMinusX.getDisplayName()}"
        prevBuildList.add(currentBuildMinusX);

        count++;
    }
    return prevBuildList
}

/*
 * Function checks if tests have been resolved by reading previous build results. 
 * The oldest build shouldn't be successfull while other builds (reqNumBuildsPassBeforeResolved) have to be green.
 *
 * @param prevBuildList List -- list of previous builds
 * @param reqNumBuldsToCountResolved Integer -- required number of builds to say tests were resolved
 *
 * @return Boolean -- list with the builds (youngest build first)
 */
def checkTestsResolved(prevBuildList, reqNumBuldsToCountResolved) {

    if (prevBuildList.size() != reqNumBuldsToCountResolved) {
        // We expect to have enough builds to count if tests were resolved
        return false
    }

    for (build in prevBuildList) {
        if (build == prevBuildList.last()) {
            // The oldest build can't be green to consider tests resolved
            if (build.getResult().toString() != "SUCCESS") {
                return true
            }
        }
        // other builds have to be green to consider tests resolved
        if (build.getResult().toString() != "SUCCESS") {
            return false
        }
    }
    return false
}


def call(args = [:]) {
    def defaultSlackMsgCallback = { return "tests failed" }
    def defaultSlackSuccessMsgCallback = { return "tests succeded" }

    // arguments to pass to execIQETests
    def appConfigs = args['appConfigs']
    def options = args.get('options', [:])
    def lockName = args.get('lockName')

    // arguments specific to slack notifier

    // OPTIONAL: if true, always send a failure notification
    def alwaysSendFailureNotification = args.get('alwaysSendFailureNotification', false)
    // OPTIONAL: if true, always send a success notification
    def alwaysSendSuccessNotification = args.get('alwaysSendSuccessNotification', false)
    // OPTIONAL: slack integration URL
    def slackUrl = args.get('slackUrl', pipelineVars.slackDefaultUrl)
    // REQUIRED: where to report test failures
    def slackChannel = args['slackChannel']
    // REQUIRED: where to report unhandled errors when this job unexpectedly fails
    def errorSlackChannel = args['errorSlackChannel']
    // OPTIONAL: closure to call that generates detailed slack msg text when tests fail
    def slackMsgCallback = args.get('slackMsgCallback', defaultSlackMsgCallback)
    // OPTIONAL: closure to call that generates detailed slack msg text when tests pass
    def slackSuccessMsgCallback = args.get('slackSuccessMsgCallback', defaultSlackSuccessMsgCallback)
    // OPTIONAL: slack team subdomain
    def slackTeamDomain = args.get('slackTeamDomain', pipelineVars.slackDefaultTeamDomain)
    // OPTIONAL: slack integration token
    def slackTokenCredentialId = args.get('slackTokenCredentialId', null)
    // currentMinusBuilds DEPRECATED
    // OPTIONAL: how many past builds to use before we mark the run as a success
    def currentMinusBuilds = args.get('currentMinusBuilds', 2)
    // OPTIONAL: how many past builds to use before we mark the run as a success
    def reqNumBuildsPassBeforeResolved = args.get('reqNumBuildsPassBeforeResolved', currentMinusBuilds)

    previousBuilds = getPreviousBuilds(reqNumBuildsPassBeforeResolved)
    def runResolved

    if (previousBuilds) {
        runResolved = checkTestsResolved(previousBuilds, reqNumBuildsPassBeforeResolved)
    }

    def results
    try {
        results = execIQETests(
            appConfigs: appConfigs,
            options: options,
            lockName: lockName
        )

        // check that we actually got results
        if (!results) error("Found no test results, unexpected error must have occurred")

        if (results['failed']) {
            if (alwaysSendFailureNotification || previousBuilds.isEmpty() || previousBuilds.first().getResult().toString() == "SUCCESS") {
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
        else if (results['success']) {
            if (alwaysSendSuccessNotification) {
                def slackMsg = slackSuccessMsgCallback()
                slackUtils.sendMsg(
                    slackChannel: slackChannel,
                    slackUrl: slackUrl,
                    slackTeamDomain: slackTeamDomain,
                    slackTokenCredentialId: slackTokenCredentialId,
                    msg: slackMsg.toString(),
                    result: "success"
                )
            }
            else if (runResolved) {
                // result went from failed -> success * reqNumBuildsPassBeforeResolved
                // no need to send notification about resolved tests in case you have alwaysSendSuccessNotification flag set to True
                slackUtils.sendMsg(
                    slackChannel: slackChannel,
                    slackUrl: slackUrl,
                    slackTeamDomain: slackTeamDomain,
                    slackTokenCredentialId: slackTokenCredentialId,
                    msg: "tests resolved",
                    result: "success"
                )
            }
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
