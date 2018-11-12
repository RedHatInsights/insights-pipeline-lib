// Test that pipenv install works, and check that the lock file is in sync with the Pipfile


lockErrorRegex = /.*Your Pipfile.lock \(\S+\) is out of date. Expected: \(\S+\).*/
lockError = "\n* `Pipfile.lock` is out of sync. Run '`pipenv lock`' and commit the changes."
installError = "\n* '`pipenv install`' has failed."


def call(scmVars) {
    sh "pip install --user --no-warn-script-location pipenv"
    sh "${pipelineVars.userPath}/pipenv run pip install --upgrade pip"

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
        if (readFile('pipenv_install_out.txt').trim() ==~ lockErrorRegex) {
            currentBuild.result = "UNSTABLE"
            errorMsg += lockError
            // try to install without the deploy flag to allow the other tests to run
            try {
                sh "${pipelineVars.userPath}/pipenv install --dev --verbose"
            } catch (err) {
                // second try without --deploy failed too, fail this build.
                echo err.getMessage()
                installFailed = true
                errorMsg += installError
            }
        } else {
            // something else failed (not a lock error), fail this build.
            echo err.getMessage()
            installFailed = true
            errorMsg += installError
        }
    }

    if (errorMsg) {
        pipfileComment.post(scmVars.GIT_COMMIT, errorMsg)
    }
    if (installFailed) {
        error("pipenv install has failed")
        ghNotify context: pipelineVars.pipInstallContext, "FAILURE"
    } else {
        ghNotify context: pipelineVars.pipInstallContext, "SUCCESS"
    }
}
