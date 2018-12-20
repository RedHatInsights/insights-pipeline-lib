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
    def helmComponentChartName = parameters['helmComponentChartName']
    def helmSmokeTestChartName = parameters['helmSmokeTestChartName']
    def pytestMarker = parameters['pytestMarker']
    def extraEnvVars = parameters.get('extraEnvVars', [:])

    withStatusContext.smoke {
        lock(label: pipelineVars.smokeTestResourceLabel, quantity: 1, variable: "PROJECT") {
            echo "Using project: ${env.PROJECT}"

            openShift.withNode(namespace: env.PROJECT) {
                runPipeline(env.PROJECT, helmComponentChartName, helmSmokeTestChartName, pytestMarker, extraEnvVars)
            }
        }
    }
}


def helm(String cmd, Boolean returnStdout = false) {
    withEnv(["TILLER_NAMESPACE=tiller"]) {
        if (returnStdout) {
            return sh(script: "helm ${cmd}", returnStdout: true).trim()
        } else {
            sh "helm ${cmd}"
        }
    }
}


def wipe(String project) {
    charts = helm("ls --namespace ${project} --short", true)
    for (String chart : charts.split()) {
        helm "delete --purge ${chart}"
    }
}


private def deployEnvironment(refspec, project, helmComponentChartName, helmSmokeTestChartName) {
    stage("Deploy test environment") {
        // Wipe old environment
        wipe(project)

        // Decrypt the secrets config
        withCredentials([file(credentialsId: 'ansible-vault-file', variable: 'FILE')]) {
            dir(pipelineVars.e2eDeployHelmDir) {
                withEnv(["ANSIBLE_VAULT_PASSWORD_FILE=$FILE"]) {
                    sh "${pipelineVars.venvDir}/bin/ansible-vault decrypt secrets.yaml.encrypted --output=secrets.yaml"
                }
            }
        }

        dir(pipelineVars.e2eDeployHelmDir) {
            try {
                // Edit values file to build this PR code locally in the test project
                sh "cp values-smoke-test.yaml values-thisrun.yaml"
                sh "echo '' >> values-thisrun.yaml"
                sh "echo '${helmComponentChartName}:' >> values-thisrun.yaml"
                sh "echo '  build:' >> values-thisrun.yaml"
                sh "echo '    install: true' >> values-thisrun.yaml"
                sh "echo '    src_repository_ref: ${refspec}' >> values-thisrun.yaml"

                // Install the smoke test chart
                helm "install charts_smoke_test/${helmSmokeTestChartName} --dep-up --name ${helmSmokeTestChartName}-smoke --values values-thisrun.yaml --values secrets.yaml --namespace ${project}"

                // Wait on all dc's to finish rolling out
                sh "oc project ${project}"
                sh "for dc in \$(oc get dc | cut -f1 -d' ' | grep -v '^NAME.*'); do oc rollout status dc \$dc -w; done"
            } finally {
                sh "rm -f secrets.yaml"
            }
        }
    }
}


private def runPipeline(
    String project,
    String helmComponentChartName,
    String helmSmokeTestChartName,
    String pytestMarker,
    Map extraEnvVars
) {
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
        checkOutRepo(targetDir: pipelineVars.e2eTestsDir, repoUrl: pipelineVars.e2eTestsRepo)
        checkOutRepo(targetDir: pipelineVars.e2eDeployHelmDir, repoUrl: pipelineVars.e2eDeployHelmRepo)
    }

    stage("Install python packages") {
        sh "python3.6 -m venv ${pipelineVars.venvDir}"
        sh "${pipelineVars.venvDir}/bin/pip install --upgrade pip setuptools"
        // Install ocdeployer for its 'list routes' functionality, and ansible for ansible-vault
        sh "${pipelineVars.venvDir}/bin/pip install ocdeployer ansible"

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
        helm "init --client-only"
        deployEnvironment(refspec, project, helmComponentChartName, helmSmokeTestChartName)
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
                ${pipelineVars.venvDir}/bin/ocdeployer list-routes ${project} --output json > routes.json
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

    openShift.collectLogs(project: project)

    stage("Wipe test environment") {
        wipe(project)
    }

    dir(pipelineVars.e2eTestsDir) {
        junit "junit.xml"
    }

    if (currentBuild.result != "SUCCESS") {
        throw new Exception("Smoke test failed");
    }
}
