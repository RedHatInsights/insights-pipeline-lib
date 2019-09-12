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


private def getRefSpec() {
    // get refspec so we can set up the OpenShift build config to point to this PR
    def refSpec = "refs/pull/${env.CHANGE_ID}/merge"

    /*
    Intermittently running into this error when running git ls-remote:
    fatal: could not read Username for 'https://github.com': No such device or address

    so skipping this check for now ...


    // Need to allocate a node to check out the repo...
    node('master') {
        // cache creds so we can git 'ls-remote' below..
        sh "git config --global credential.helper cache"

        sh "mkdir pr_source_${BUILD_ID}"        
        dir("pr_source_${BUILD_ID}") {
            checkout scm

            // Ensure that the refspec exists in the repo
            stage("Check refspec") {
                refSpecExists = sh(returnStdout: true, script: "git ls-remote | grep ${refSpec}").trim()
            }
        }

        sh "rm -fr pr_source_${BUILD_ID}"
    }

    if (!refSpecExists) {
        error("Unable to find git refspec: ${refSpec}")
    }
    */
    return refSpec
}


private def deployEnvironment(refSpec, project, ocDeployerBuilderPath, ocDeployerComponentPath, ocDeployerServiceSets) {
    stage("Deploy test environment") {
        dir(pipelineVars.e2eDeployDir) {
            // Deploy the builder for only this app to build the PR image in this project
            def builderTask = {
                sh "echo \"${ocDeployerBuilderPath}:\" > builder-env.yml"
                sh "echo \"  parameters:\" >> builder-env.yml"
                sh "echo \"    SOURCE_REPOSITORY_REF: ${refSpec}\" >> builder-env.yml"
                sh "cat builder-env.yml"
                if (ocDeployerBuilderPath.contains("/")) {
                    sh "ocdeployer deploy -f -l e2esmoke=true -p ${ocDeployerBuilderPath} -t buildfactory -e env/smoke.yml -e builder-env.yml ${project}"
                } else {
                    sh "ocdeployer deploy -f -l e2esmoke=true -s ${ocDeployerBuilderPath} -t buildfactory -e env/smoke.yml -e builder-env.yml ${project}"
                }
            }

            // Also deploy the test env apps, but set the image for the PR app to be pulled from this local project instead of buildfactory
            def serviceTask = {
                sh "echo \"${ocDeployerComponentPath}:\" > env.yml"
                sh "echo \"  parameters:\" >> env.yml"
                sh "echo \"    IMAGE_NAMESPACE: ${project}\" >> env.yml"
                sh "echo \"    IMAGE_TAG: latest\" >> env.yml"
                sh "cat env.yml"   
                sh "ocdeployer deploy -f -l e2esmoke=true -s ${ocDeployerServiceSets} -e env/smoke.yml -e env.yml ${project}"
            }

            // Run the deployments in parallel
            parallel([
                "Deploy custom buildConfig": builderTask,
                "Deploy service sets: ${ocDeployerServiceSets}": serviceTask
            ])
        }
    }
}


private def runPipeline(
    String refSpec, String project, String ocDeployerBuilderPath, String ocDeployerComponentPath,
    String ocDeployerServiceSets, String pytestMarker, List<String> iqePlugins, Map extraEnvVars,
    String configFileCredentialsId
) {
    cancelPriorBuilds()

    currentBuild.result = "SUCCESS"

    /* Deploy a test env to 'project' in openshift, checkout e2e-tests, run the smoke tests */

    // check out e2e-deploy
    stage("Check out repos") {
        checkOutRepo(targetDir: pipelineVars.e2eDeployDir, repoUrl: pipelineVars.e2eDeployRepo, credentialsId: "InsightsDroidGitHubHTTP")
    }

    stage("Install ocdeployer") {
        sh "devpi use http://devpi.devpi.svc:3141/root/psav --set-cfg"
        sh "devpi refresh ocdeployer"

        dir(pipelineVars.e2eDeployDir) {
            sh "pip install -r requirements.txt"
        }
    }

    stage("Wipe test environment") {
        sh "ocdeployer wipe -l e2esmoke=true --no-confirm ${project}"
    }

    stage("Install iqe and plugins") {
        sh "pip install --upgrade iqe-integration-tests"
        sh "pip install --upgrade iqe-red-hat-internal-envs-plugin"
        for (String plugin : iqePlugins) {
            sh "pip install ${plugin}"
        }
    }

    try {
        deployEnvironment(refSpec, project, ocDeployerBuilderPath, ocDeployerComponentPath, ocDeployerServiceSets)
    } catch (err) {
        echo("Hit error during deploy!")
        echo(err.toString())
        openShift.collectLogs(project: project)
        throw err
    }

    if (configFileCredentialsId) {
        stage("Inject custom config") {
            withCredentials([file(credentialsId: configFileCredentialsId, variable: 'SETTINGS_YAML')]) {
                sh "cp \$SETTINGS_YAML \"\$WORKSPACE/settings.local.yaml\""
            }
        }
    }

    stage("Run tests (pytest marker: ${pytestMarker})") {
        extraEnvVars.each { key, val ->
            sh "export ${key}=${val}"
        }

        sh """
            export ENV_FOR_DYNACONF=smoke
            export DYNACONF_OCPROJECT=${project}
            export IQE_TESTS_LOCAL_CONF_PATH="$WORKSPACE"

            set +e
            iqe tests all --junitxml=junit.xml -s -v -m ${pytestMarker} --log-file=iqe.log --log-file-level=DEBUG 2>&1 | tee pytest-stdout.log
            set -e
        """
        try {
            archiveArtifacts "pytest-stdout.log"
            archiveArtifacts "iqe.log"
        } catch (err) {
            echo "Error archiving log files: ${err.toString()}"
        }
    }

    openShift.collectLogs(project: project)

    stage("Wipe test environment") {
        sh "ocdeployer wipe -l e2esmoke=true --no-confirm ${project}"
    }

    junit "junit.xml"

    if (currentBuild.result != "SUCCESS") {
        throw new Exception("Smoke test failed");
    }
}


private def allocateResourcesAndRun(
    String refSpec, String ocDeployerBuilderPath, String ocDeployerComponentPath, String ocDeployerServiceSets,
    String pytestMarker, List<String> iqePlugins, Map extraEnvVars, String configFileCredentialsId
) {
    // Reserve a smoke test project, spin up a slave pod, and run the test pipeline
    lock(label: pipelineVars.smokeTestResourceLabel, quantity: 1, variable: "PROJECT") {
        echo "Using project: ${env.PROJECT}"

        openShift.withNode(image: 'docker-registry.default.svc:5000/jenkins/jenkins-slave-iqe:latest', namespace: env.PROJECT) {
            runPipeline(refSpec, env.PROJECT, ocDeployerBuilderPath, ocDeployerComponentPath, 
                        ocDeployerServiceSets, pytestMarker, iqePlugins, extraEnvVars, configFileCredentialsId)
        }
    }
}


private def setParamDefaults(String refSpec) {
    properties(
        [parameters([
            string(
                name: 'GIT_REF',
                defaultValue: refSpec,
                description: 'The git ref to deploy for this app during the smoke test'
            )
        ])]
    )
}


def call(p = [:]) {
    def ocDeployerBuilderPath = p['ocDeployerBuilderPath']
    def ocDeployerComponentPath = p['ocDeployerComponentPath']
    def ocDeployerServiceSets = p['ocDeployerServiceSets']
    def pytestMarker = p['pytestMarker']
    def iqePlugins = p.get('iqePlugins')
    def extraEnvVars = p.get('extraEnvVars', [:])
    def configFileCredentialsId = p.get('configFileCredentialsId', "")

    // If testing via a PR webhook trigger
    if (env.CHANGE_ID) {
        // Set the 'stable' label on the PR
        try {
            if (env.CHANGE_TARGET == 'stable') pullRequest.addLabels(['stable'])
        } catch (err) {
            echo "Failed to set 'stable' label: ${err.getMessage()}}"
        }

        // Get the refspec of the PR
        def refSpec = getRefSpec()

        // Define a string parameter to set the git ref on manual runs
        setParamDefaults(refSpec)

        // Run the job using github status notifications so the test status is reported to the PR
        withStatusContext.smoke {
            allocateResourcesAndRun(
                refSpec, ocDeployerBuilderPath, ocDeployerComponentPath, ocDeployerServiceSets,
                pytestMarker, iqePlugins, extraEnvVars, configFileCredentialsId
            )
        }
    // If testing via a manual trigger... we have no PR, so don't notify github or interact with a github PR
    } else {
        // Define a string parameter to set the git ref on manual runs
        setParamDefaults(env.BRANCH_NAME ? env.BRANCH_NAME : "master")
        // Grab the value of the parameter passed in by the user
        def refSpec = params["GIT_REF"]
        allocateResourcesAndRun(
            refSpec, ocDeployerBuilderPath, ocDeployerComponentPath, ocDeployerServiceSets,
            pytestMarker, iqePlugins, extraEnvVars, configFileCredentialsId
        )
    }
}
