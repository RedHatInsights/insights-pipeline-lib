def postPipfileComment(parameters = [:]) {
    // Comment on a github PR indicating that a Pipfile is in a bad state
    def commitId = parameters['commitId']
    def str = parameters['str']

    def shortId = commitId.substring(0, 7)
    def body = "Commit `${shortId}` Pipfile violation\n${str}"
    try {
        pullRequest.comment(body)
    } catch (err) {
        msg = err.getMessage()
        echo "Error adding Pipfile comment: ${msg}"
    }
}


def removePipfileComments() {
    // Remove all comments on a github PR indicating that a Pipfile is in a bad state
    try {
        for (comment in pullRequest.comments) {
            if (comment.body.contains("Pipfile violation")) {
                comment.delete()
            }
        }
    } catch (err) {
        msg = err.getMessage()
        echo "Error removing Pipfile comments: ${msg}"
    }
}




def runPipenvInstall(parameters = [:]) {
    // Test that pipenv install works, and check that the lock file is in sync with the Pipfile
    def scmVars = parameters['scmVars']
    def installPipenv = parameters.get('installPipenv', true)
    def sequential = parameters.get('sequential', false)

    // Common errors we may hit ...
    def lockErrorRegex = ~/.*Your Pipfile.lock \(\S+\) is out of date. Expected: \(\S+\).*/
    def lockError = "\n* `Pipfile.lock` is out of sync. Run '`pipenv lock`' and commit the changes."
    def installError = "\n* '`pipenv install`' has failed."

    if (installPipenv) sh "pip install --user --upgrade pip setuptools wheel pipenv"

    // NOTE: Removing old comments won't work unless Pipeline Github Plugin >= v2.0
    removePipfileComments()

    def sequentialArg = sequential ? "--sequential" : ""

    // use --deploy to check if Pipfile and Pipfile.lock are in sync
    def cmdStatus = sh(
        script: (
            "${pipelineVars.userPath}/pipenv install --dev --deploy --verbose " +
            "${sequentialArg} > pipenv_install_out.txt"
        ),
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
                script: (
                    "${pipelineVars.userPath}/pipenv install --dev --verbose " +
                    "${sequentialArg} > pipenv_install_out.txt"
                ),
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
        postPipfileComment(commitId: scmVars.GIT_COMMIT, str: errorMsg)
    }
    if (installFailed) {
        gitUtils.ghNotify context: gitUtils.getStatusContext("pipinstall"), status: "FAILURE"
        error("pipenv install has failed")
    } else {
        gitUtils.ghNotify context: gitUtils.getStatusContext("pipinstall"), status: "SUCCESS"
    }
}


def runLintCheck(parameters = [:]) {
    // Run a lint check using either flake8 or pylama (with the pytest plugin)
    def pylama = parameters.get('pylama', false)

    gitUtils.withStatusContext("lint") {
        if (pylama) {
            sh(
                "${pipelineVars.userPath}/pipenv run python -m pytest --pylama " +
                "--junitxml=lint-results.xml --ignore=tests/"
            )
        } else {
            sh "${pipelineVars.userPath}/pipenv run flake8 . --output-file lint-results.txt"
        }
    }

    try {
        if (fileExists("lint-results.txt")) {
            sh "${pipelineVars.userPath}/pipenv run flake8_junit lint-results.txt lint-results.xml"
        }
        junit 'lint-results.xml'
    } catch (evalErr) {
        // allow the unit tests to run even if evaluating lint results failed...
        echo evalErr.getMessage()
    }
}


def checkCoverage(parameters = [:]) {
    // Check that code coverage isn't below a certain threshold
    // Assumes that python code coverage has already been run
    gitUtils.withStatusContext("coverage") {
        def threshold = parameters.get('threshold', 80)

        def status = 99

        status = sh(
            script: (
                "${pipelineVars.userPath}/pipenv run coverage html " +
                "--fail-under=${threshold} --skip-covered"
            ),
            returnStatus: true
        )

        archiveArtifacts 'htmlcov/*'

        if (status != 0) { 
            throw new Exception("Code coverage is below threshold of ${threshold}%")
        }
    }
}
