/*
 *
 * An example Pipeline which uses the library to test a python project
 *
 * Requires: https://github.com/RedHatInsights/insights-pipeline-lib
 */

@Library("github.com/RedHatInsights/insights-pipeline-lib") _

// Code coverage failure threshold
codecovThreshold = 80


node {
    // Cancel any prior builds that are running for this job
    cancelPriorBuilds()

    // Only runs runStages() if this job was triggered by a change to the master branch, or a PR
    runIfMasterOrPullReq {
        runStages()
    }
}


def runStages() {
    // withNode is a helper to spin up a jnlp slave using the Kubernetes plugin, and run the body code on that slave
    openShift.withNode(image: "centos/python-36-centos7") {
        // check out source again to get it in this node's workspace
        scmVars = checkout scm

        stage('Pip install') {
            // Helper for projects using pipenv, verifies pip install works, verifies Pipfile is in sync with Pipfile.lock
            // Notifies GitHub with the "continuous-integration/jenkins/pipinstall" status
            runPipenvInstall(scmVars: scmVars)
        }

        stage('Lint') {
            // Runs flake8 lint check and stores results.
            // Notifies GitHub with the "continuous-integration/jenkins/lint" status
            runPythonLintCheck()
        }

        stage('UnitTest') {
            // withStatusContext runs the body code and notifies GitHub on whether it passed or failed
            // 'unitTest' will notify the "continuous-integration/jenkins/unittest" status
            withStatusContext.unitTest {
                sh "${pipelineVars.userPath}/pipenv run python -m pytest --junitxml=junit.xml --cov=service --cov=db --cov-report html tests/ -s -v"
            }
            junit 'junit.xml'
        }

        stage('Code coverage') {
            // Checks code coverage results of the above unit tests with coverage.py, this step fails if coverage is below codecovThreshold
            // Notifies GitHub with the "continuous-integration/jenkins/coverage" status
            checkCoverage(threshold: codecovThreshold)
        }

        if (currentBuild.currentResult == 'SUCCESS') {
            if (env.BRANCH_NAME == 'master') {
                // Stages to run specifically if master branch was updated
            }
        }
    }
}
