// Test that pipenv install works, and check that the lock file is in sync with the Pipfile




def call(parameters = [:]) {
    scmVars = parameters['scmVars']

    // Common errors we may hit ...
    def lockErrorRegex = ~/.*Your Pipfile.lock \(\S+\) is out of date. Expected: \(\S+\).*/
    def lockError = "\n* `Pipfile.lock` is out of sync. Run '`pipenv lock`' and commit the changes."
    def installError = "\n* '`pipenv install`' has failed."

    sh "pip install --user --upgrade pip setuptools wheel pipenv"
    // NOTE: Removing old comments won't work unless Pipeline Github Plugin >= v2.0
    pipfileComment.removeAll()

    // use --deploy to check if Pipfile and Pipfile.lock are in sync
    def cmdStatus = sh(
        script: "${pipelineVars.userPath}/pipenv install --dev --deploy --verbose > pipenv_install_out.txt",
        returnStatus: true
    )

    def installFailed = false
    def errorMsg = ""
    if (cmdStatus != 0) {
        if (readFile("pipenv_install_out.txt").trim() ==~ lockErrorRegex) {
            currentBuild.result = "UNSTABLE"
            errorMsg += lockError
            // try to install without the deploy flag to allow the other tests to run
            cmdStatus = sh(
                script: "${pipelineVars.userPath}/pipenv install --dev --verbose > pipenv_install_out.txt",
                returnStatus: true
            )
            if (cmdStatus != 0) {
                // second try without --deploy failed too, fail this build.
                installFailed = true
                errorMsg += installError
            }
        } else {
            // something else failed (not a lock error), fail this build.
            installFailed = true
            errorMsg += installError
        }
    }

    archiveArtifacts("pipenv_install_out.txt")
    if (errorMsg) {
        pipfileComment.post(commitId: scmVars.GIT_COMMIT, str: errorMsg)
    }
    if (installFailed) {
        ghNotify context: pipelineVars.pipInstallContext, status: "FAILURE"
        error("pipenv install has failed")
    } else {
        ghNotify context: pipelineVars.pipInstallContext, status: "SUCCESS"
    }
}
