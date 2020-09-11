/*
 * Runs execIQETests and wraps it with a slack notifier that behaves in the following way:
 *
 * 1. sends a slack msg only when test results change from fail->pass or pass->fail
 * 2. for failed tests, a callback function can be used to generate your own custom error msg
 * 3. ignores jobs that have a "reload" status
 * 4. sends a slack msg to a separate channel when an unhandled error occurs (e.g. job errors
        unrelated to the test itself failing)
 */


def call(args = [:]) {
    def defaultSlackMsgCallback = { return "test failed" }

    // arguments to pass to execIQETests
    def appConfigs = args['appConfigs']
    def envs = args['envs']
    def options = args.get('options', [:])
    def defaultMarker = args.get('defaultMarker')
    def defaultFilter = args.get('defaultFilter')
    def extraJobProperties = args.get('extraJobProperties', [])
    def lockName = args.get('lockName')

    // arguments specific to slack notifier
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
    def slackTokenCredentialId = args.get('slackTokenCredentialId', pipelineVars.slackDefaultTokenCredentialId)

    def currentMinusOne = pipelineUtils.getLastRealBuild(currentBuild)
    if (currentMinusOne) {
        echo "Found currentMinusOne non-RELOAD/non-ERROR build: ${currentMinusOne.getDisplayName()}"
    }

    def currentMinusTwo = pipelineUtils.getLastRealBuild(currentMinusOne)
    if (currentMinusTwo) {
        echo "Found currentMinusTwo non-RELOAD/non-ERROR build: ${currentMinusTwo.getDisplayName()}"
    }

    def results
    try {
        results = execIQETests(
            appConfigs: appConfigs,
            envs: envs,
            options: options,
            defaultMarker: defaultMarker,
            defaultFilter: defaultFilter,
            extraJobProperties: extraJobProperties,
            lockName: lockName
        )

        // check that we actually got results
        if (!results) error("Found no test results, unexpected error must have occurred")

        if (results['failed']) {
            if (!currentMinusOne || currentMinusOne.getResult().toString() == "SUCCESS") {
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
        else if (currentMinusOne && currentMinusTwo && currentMinusOne.getResult().toString() == "SUCCESS" && currentMinusTwo.getResult().toString() != "SUCCESS") {
            // result went from failed -> success -> success
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
                msg: "hit unhandled error",
                result: "failure"
            )
            throw err
        }
    }

    if (results['failed']) {
        error("Tests failed for apps: ${results['failed'].join(', ')}")
    }
}
