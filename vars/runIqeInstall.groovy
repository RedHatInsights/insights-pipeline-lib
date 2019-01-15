// Install iqe-integration-tests


def call(parameters = [:]) {
    pluginName = parameters["pluginName"]

    sh "devpi use $DEV_PI --set-cfg"
    def cmdStatus = sh(
        script: "pip install --user --no-warn-script-location iqe-integration-tests ${pluginName}",
        returnStatus: true
    )

    // if (cmdStatus != 0) {
    //     error("pip install has failed")
    //     ghNotify context: pipelineVars.pipInstallContext, status: "FAILURE"
    // } else {
    //     ghNotify context: pipelineVars.pipInstallContext, status: "SUCCESS"
    // }

}