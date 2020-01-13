/*
 * Run an e2e smoke test
 *
 * Required plugins:
 *  Blue Ocean / all the 'typical' plugins for GitHub multi-branch pipelines
 *  GitHub Branch Source Plugin
 *  SCM Filter Branch PR Plugin
 *  Pipeline GitHub Notify Step Plugin
 *  Pipeline: GitHub Plugin
 *  SSH Agent Plugin
 *  Lockable Resources Plugin
 *  Kubernetes Plugin
 *
 * Configuration:
 *  Discover branches:
 *    Strategy: Exclude branches that are also filed as PRs
 *  Discover pull requests from forks:
 *    Strategy: Merging the pull request with the current target branch revision
 *    Trust: From users with Admin or Write permission
 *  Discover pull requests from origin
 *    Strategy: Merging the pull request with the current target branch revision
 *  Filter by name including PRs destined for this branch (with regular expression):
 *    Regular expression: .*
 *  Clean before checkout
 *  Clean after checkout
 *  Check out to matching local branch
 *
 * Script whitelisting is needed for 'currentBuild.rawBuild' and 'currentBuild.rawBuild.getCause()'
 *
 * Create projects in OpenShift, assign them to a lockable resource w/ label "smoke_test_projects"
 * Add Jenkins service account as admin on those projects
 *
 */


private def getRefSpec() {
    // get refspec so we can set up the OpenShift build config to point to this PR
    return "refs/pull/${env.CHANGE_ID}/merge"
}


private def deployEnvironment(
    refSpec, project, ocDeployerBuilderPath, ocDeployerComponentPath, ocDeployerServiceSets
) {
    /**
     * Pipeline stages for running ocdeployer.
     *
     * Sets up a custom env file to point the build config for the component at
        'ocDeployerBuilderPath' to use the custom 'refSpec'
     * Deploys the build config into the ephemeral namespace
        if ocDeployerBuildPath is a 'serviceSet/component' only that component is deployed
        if it is a 'serviceSet' the whole service set is deployed
     * Sets up a custom env file to point the app for the component at `ocDeployerComponentPath`
        to use the image produced by the above custom build config
     * Deploys the specified `ocDeployerServiceSets` using the above config
     */
    stage("Deploy test environment") {
        dir(pipelineVars.e2eDeployDir) {
            // Deploy the builder for only this app to build the PR image in this project
            def builderTask = {
                def customBuildYaml = (
                    """
                    "${ocDeployerBuilderPath}":
                        parameters:
                            SOURCE_REPOSITORY_REF: ${refSpec}
                    """
                ).stripIndent()
                writeFile file: "builder-env.yml", text: customBuildYaml
                sh "cat builder-env.yml"
                def pickArg = ocDeployerBuilderPath.contains("/") ? "-p" : "-s"
                sh(
                    "ocdeployer deploy -w -f -l e2esmoke=true ${pickArg} " +
                    "${ocDeployerBuilderPath} -t buildfactory -e builder-env.yml " +
                    "-e env/smoke.yml ${project}"
                )
            }

            // Also deploy the test env apps, but set the image for the PR app to be pulled
            // from this local project instead of buildfactory
            def serviceTask = {
                def customAppYaml = (
                    """
                    "${ocDeployerComponentPath}":
                        parameters:
                            IMAGE_NAMESPACE: ${project}
                            IMAGE_TAG: latest
                    """
                ).stripIndent()
                writeFile file: "custom-env.yml", text: customAppYaml
                sh "cat custom-env.yml"   
                sh(
                    "ocdeployer deploy -w -f -l e2esmoke=true -s ${ocDeployerServiceSets} " +
                    "-e custom-env.yml -e env/smoke.yml ${project}"
                )
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
    /* Deploy a test env to 'project' in openshift, checkout e2e-tests, run the smoke tests */

    pipelineUtils.cancelPriorBuilds()
    currentBuild.result = "SUCCESS"

    // check out e2e-deploy
    stage("Check out repos") {
        gitUtils.checkOutRepo(
            targetDir: pipelineVars.e2eDeployDir,
            repoUrl: pipelineVars.e2eDeployRepo,
            credentialsId: "InsightsDroidGitHubHTTP"
        )
    }

    pipelineUtils.stageIf(iqePlugins, "Install plugins") {
        for (plugin in iqePlugins) {
            sh "pip install ${plugin}"
        }
    }

    // wipe all resources that have label 'e2esmoke=true'
    stage("Wipe test environment") {
        sh "ocdeployer wipe -l e2esmoke=true --no-confirm ${project}"
    }

    try {
        deployEnvironment(
            refSpec, project, ocDeployerBuilderPath, ocDeployerComponentPath, ocDeployerServiceSets
        )
    } catch (err) {
        echo("Hit error during deploy!")
        echo(err.toString())
        openShiftUtils.collectLogs(project: project)
        throw err
    }

    if (configFileCredentialsId) {
        stage("Inject custom config") {
            withCredentials(
                [file(credentialsId: configFileCredentialsId, variable: 'SETTINGS_YAML')]
            ) {
                sh "cp \$SETTINGS_YAML \"\$WORKSPACE/settings.local.yaml\""
            }
        }
    }

    stage("Run tests (pytest marker: ${pytestMarker})") {
        extraEnvVars.each { key, val ->
            sh "export ${key}=${val}"
        }

        // tee the output -- the 'junit' step later will change build status if any tests fail
        iqeCommand = (
            "iqe tests all --junitxml=junit.xml -s -v -m ${pytestMarker} --log-file=iqe.log " +
            "--log-file-level=DEBUG 2>&1 | tee pytest-stdout.log"
        )

        sh(
            """
            export DYNACONF_OCPROJECT=${project}
            export IQE_TESTS_LOCAL_CONF_PATH="$WORKSPACE"

            set +e
            ${iqeCommand}
            set -e
            """.stripIndent()
        )
        try {
            archiveArtifacts "pytest-stdout.log"
            archiveArtifacts "iqe.log"
        } catch (err) {
            echo "Error archiving log files: ${err.toString()}"
        }
    }

    openShiftUtils.collectLogs(project: project)

    stage("Wipe test environment") {
        sh "ocdeployer wipe -l e2esmoke=true --no-confirm ${project}"
    }

    junit "junit.xml"

    if (currentBuild.result != "SUCCESS") {
        error("Smoke test failed");
    }
}


private def allocateResourcesAndRun(
    String refSpec, String ocDeployerBuilderPath, String ocDeployerComponentPath,
    String ocDeployerServiceSets, String pytestMarker, List<String> iqePlugins, Map extraEnvVars,
    String configFileCredentialsId
) {
    // Reserve a smoke test project, spin up a slave pod, and run the test pipeline
    lock(label: pipelineVars.smokeTestResourceLabel, quantity: 1, variable: "PROJECT") {
        echo "Using project: ${env.PROJECT}"

        envVars = [envVar(key: 'ENV_FOR_DYNACONF', value: 'smoke')]
        openShiftUtils.withNode(
            image: pipelineVars.iqeCoreImage, namespace: env.PROJECT, envVars: envVars
        ) {
            runPipeline(
                refSpec, env.PROJECT, ocDeployerBuilderPath, ocDeployerComponentPath, 
                ocDeployerServiceSets, pytestMarker, iqePlugins, extraEnvVars,
                configFileCredentialsId
            )
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
        gitUtils.withStatusContext("e2e-smoke") {
            allocateResourcesAndRun(
                refSpec, ocDeployerBuilderPath, ocDeployerComponentPath, ocDeployerServiceSets,
                pytestMarker, iqePlugins, extraEnvVars, configFileCredentialsId
            )
        }
    // If testing via a manual trigger... we have no PR, so don't notify github/try to add PR label
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
