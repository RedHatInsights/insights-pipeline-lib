/*
 * Run an e2e smoke test
 *
 * Required plugins:
 * Blue Ocean / all the 'typical' plugins for GitHub multi-branch pipelines
 * GitHub Branch Source Plugin
 * SCM Filter Branch PR Plugin
 * Pipeline GitHub Notify Step Plugin
 * Pipeline: GitHub Plugin
 * SSH Agent Plugin
 * Lockable Resources Plugin
 * Kubernetes Plugin
 *
 * Configuration:
 * Discover branches:
 *   Strategy: Exclude branches that are also filed as PRs
 * Discover pull requests from forks:
 *   Strategy: Merging the pull request with the current target branch revision
 *   Trust: From users with Admin or Write permission
 * Discover pull requests from origin
 *   Strategy: Merging the pull request with the current target branch revision
 * Filter by name including PRs destined for this branch (with regular expression):
 *   Regular expression: .*
 * Clean before checkout
 * Clean after checkout
 * Check out to matching local branch
 *
 * Script whitelisting is needed for 'currentBuild.rawBuild' and 'currentBuild.rawBuild.getCause()'
 *
 * Configure projects in OpenShift, create lockable resources for them with label "smoke_test_projects"
 * Add Jenkins service account as admin on those projects
 *
 */

def call(parameters = [:]) {
    def ocDeployerBuilderPath = parameters['ocDeployerBuildPath']
    def ocDeployerComponentPath = parameters['ocDeployerComponentPath']
    def ocDeployerServiceSets = parameters['ocDeployerServiceSets']
    def pytestMarker = parameters['pytestMarker']
    def extraEnvVars = parameters.get('extraEnvVars', [:])

    withStatusContext.smoke {
        lock(label: pipelineVars.smokeTestResourceLabel, quantity: 1, variable: "PROJECT") {
            echo "Using project: ${env.PROJECT}"

            openShift.withNode(namespace: env.PROJECT) {
                runPipeline(env.PROJECT, ocDeployerBuilderPath, ocDeployerComponentPath, 
                            ocDeployerServiceSets, pytestMarker, extraEnvVars)
            }
        }
    }
}


private def deployEnvironment(refspec, project, ocDeployerBuilderPath, ocDeployerComponentPath, ocDeployerServiceSets) {
    stage("Deploy test environment") {
        dir(pipelineVars.e2eDeployDir) {
            // First, deploy the builder for only this app to build the PR image in this project
            sh "echo \"${ocDeployerBuilderPath}:\" > env.yml"
            sh "echo \"  SOURCE_REPOSITORY_REF: ${refspec}\" >> env.yml"
            sh  "${pipelineVars.venvDir}/bin/ocdeployer --pick ${ocDeployerBuilderPath} --template-dir buildfactory -e env.yml --secrets-src-project secrets --no-confirm ${project}"

            // Now deploy the full env, set the image for this app to be pulled from this local project instead of buildfactory
            sh "echo \"${ocdeployerComponentPath}:\" > env.yml"
            sh "echo \"  IMAGE_NAMESPACE: ${project}\" >> env.yml"   
            sh  "${pipelineVars.venvDir}/bin/ocdeployer -s ${ocdeployerServiceSets} -e env.yml --secrets-src-project secrets --no-confirm ${project}"
        }
    }
}


private def runPipeline(String project, String ocDeployerBuilderPath, String ocDeployerComponentPath,
                        String ocDeployerServiceSets, String pytestMarker, Map extraEnvVars) {
    cancelPriorBuilds()

    currentBuild.result = "SUCCESS"

    /* Deploy a test env to 'project' in openshift, checkout e2e-tests, run the smoke tests */

    // cache creds so we can git 'ls-remote' below...
    sh "git config --global credential.helper cache"
    checkout scm

    // get refspec so we can set up the OpenShift build config to point to this PR
    // there's gotta be a better way to get the refspec, somehow 'checkout scm' knows what it is ...
    def refspec

    stage("Get refspec") {
        refspec = "refs/pull/${env.CHANGE_ID}/merge"
        def refspecExists = sh(returnStdout: true, script: "git ls-remote | grep ${refspec}").trim()
        if (!refspecExists) {
            error("Unable to find git refspec: ${refspec}")
        }
    }

    // check out e2e-tests
    stage("Check out repos") {
        checkoutRepo(pipelineVars.e2eTestsDir, pipelineVars.e2eTestsRepo)
        checkoutRepo(pipelineVars.e2eDeployDir, pipelineVars.e2eDeployRepo)
    }

    stage("Install ocdeployer") {
        sh """
            python3.6 -m venv ${pipelineVars.venvDir}
            ${pipelineVars.venvDir}/bin/pip install --upgrade pip
        """
        dir(pipelineVars.e2eDeployDir) {
            sh "${pipelineVars.venvDir}/bin/pip install -r requirements.txt"
        }
    }

    stage("Wipe test environment") {
        sh "${pipelineVars.venvDir}/bin/ocdeployer -w --no-confirm ${project}"
    }

    stage("Install e2e-tests") {
        dir(pipelineVars.e2eTestsDir) {
            // Use sshagent so we can clone github private repos referenced in requirements.txt
            sshagent(credentials: [pipelineVars.gitSshCreds]) {
                sh """
                    mkdir -p ~/.ssh
                    touch ~/.ssh/known_hosts
                    cp ~/.ssh/known_hosts ~/.ssh/known_hosts.backup
                    ssh-keyscan -t rsa github.com > ~/.ssh/known_hosts
                    ${pipelineVars.venvDir}/bin/pip install -r requirements.txt
                    cp ~/.ssh/known_hosts.backup ~/.ssh/known_hosts
                """
            }
        }
    }

    try {
        deployEnvironment(refspec, project, ocDeployerBuilderPath, ocDeployerComponentPath, ocDeployerServiceSets)
    } catch (err) {
        openShift.collectLogs(project)
        throw err
    }

    stage("Run tests (pytest marker: ${pytestMarker})") {
        /*
         * Set up the proper env vars for the tests by getting current routes from the OpenShfit project,
         * then run the tests with the proper smoke marker for this app/service.
         *
         * NOTE: 'tee' is used when running pytest so we always get a "0" return code. The 'junit' step
         * will take care of failing the build if any tests fail...
         */
        dir(pipelineVars.e2eTestsDir) {
            extraEnvVars.each { key, val ->
                sh "export ${key}=${val}"
            }

            sh """
                ${pipelineVars.venvDir}/bin/ocdeployer --list-routes ${project} --output json > routes.json
                cat routes.json
                ${pipelineVars.venvDir}/bin/python envs/convert-from-ocdeployer.py routes.json env_vars.sh
                cat env_vars.sh
                . ./env_vars.sh
                export OCP_ENV=${project}

                ${pipelineVars.venvDir}/bin/python -m pytest --junitxml=junit.xml -s -v -m ${pytestMarker} 2>&1 | tee pytest.log
            """

            archiveArtifacts "pytest.log"
        }
    }

    openShift.collectLogs(project)

    stage("Wipe test environment") {
        sh "${pipelineVars.venvDir}/bin/ocdeployer -w --no-confirm ${project}"
    }

    dir(pipelineVars.e2eTestsDir) {
        junit "junit.xml"
    }
}
