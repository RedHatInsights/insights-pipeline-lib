// Test that bundle install works


def call(parameters = [:]) {

    def cmdStatus = sh(
        script: "bundle install",
        returnStatus: true
    )

    if (cmdStatus != 0) {
        error("bundle install has failed")
        ghNotify context: pipelineVars.bundleInstallContext, status: "FAILURE"
    } else {
        ghNotify context: pipelineVars.bundleInstallContext, status: "SUCCESS"
    }
}
