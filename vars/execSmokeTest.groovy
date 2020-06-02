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
    refSpec, project, ocDeployerBuilderPath, ocDeployerComponentPath, ocDeployerServiceSets,
    buildScaleFactor, parallelBuild
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
    stage("Create env files") {
        // deploy custom build config that points to this app's PR code
        def customBuildYaml = (
            """
            "${ocDeployerBuilderPath}":
                parameters:
                    SOURCE_REPOSITORY_REF: ${refSpec}
            """
        ).stripIndent()
        writeFile file: "env/builder-env.yml", text: customBuildYaml
        sh "cat env/builder-env.yml"

        // set image for the PR app to be pulled from this local namespace
        def customAppYaml = (
            """
            "${ocDeployerComponentPath}":
                parameters:
                    IMAGE_NAMESPACE: ${project}
                    IMAGE_TAG: latest
            """
        ).stripIndent()
        writeFile file: "env/custom-env.yml", text: customAppYaml
        sh "cat env/custom-env.yml"
    }

    def deployTasks = [:]

    // Deploy the builder for only this app to build the PR image in this project
    // Make this a closure so we can decide whether to run it stand-alone or in parallel later...
    def buildTask = {
        def pickArg = ocDeployerBuilderPath.contains("/") ? "-p" : "-s"
        // use --scale-resources to beef up the build resources to help the app build quickly
        sh(
            "ocdeployer deploy -w -f -l e2esmoke=true ${pickArg} " +
            "${ocDeployerBuilderPath} -t buildfactory -e builder-env " +
            "-e smoke ${project} --scale-resources ${buildScaleFactor} --secrets-src-project secrets"
        )
    }

    // if running build in parallel, the build will run while the services are deploying
    // if this is not feasible because the build takes too long and the service deploys time out,
    // then the build can be run prior to the app deployment
    if (parallelBuild) deployTasks['Deploy buildConfig'] = buildTask
    else {
        stage("Deploy buildConfig") {
            buildTask()
        }
    }

    // Deploy the other service sets
    for (serviceSet in ocDeployerServiceSets.split(',')) {
        def set = serviceSet // https://jenkins.io/doc/pipeline/examples/#parallel-multiple-nodes
        deployTasks["Deploy ${serviceSet}"] = {
            sh(
                "ocdeployer deploy -w -f -l e2esmoke=true -s ${set} " +
                "-e custom-env -e smoke ${project} --secrets-src-project secrets"
            )
        }
    }

    // Run the service deployments in parallel
    parallel(deployTasks)
}


private def runPipeline(
    String refSpec, String project, String ocDeployerBuilderPath, String ocDeployerComponentPath,
    String ocDeployerServiceSets, pytestMarker, List<String> iqePlugins, Map extraEnvVars,
    String configFileCredentialsId, int buildScaleFactor, int parallelWorkerCount,
    Boolean parallelBuild, String cloud, Boolean ui
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
        dir(pipelineVars.e2eDeployDir) {
            sh "pip install -r requirements.txt"
        }
    }

    // wipe all resources that have label 'e2esmoke=true'
    stage("Wipe test environment") {
        sh "ocdeployer wipe -l e2esmoke=true --no-confirm ${project}"
    }

    try {
        dir(pipelineVars.e2eDeployDir) {
            deployEnvironment(
                refSpec, project, ocDeployerBuilderPath, ocDeployerComponentPath,
                ocDeployerServiceSets, buildScaleFactor, parallelBuild
            )
        }
    } catch (err) {
        echo("Hit error during deploy!")
        echo(err.toString())
        openShiftUtils.collectLogs(project: project)
        error("Deployment failed")
    }

    // create the appConfig used by iqeUtils
    def appConfigs = [
        smoke: [
            plugins: iqePlugins,
            ui: ui,
            parallelWorkerCount: parallelWorkerCount,
            settingsFileCredentialsId: configFileCredentialsId,
            extraEnvVars: [envVar(key: 'DYNACONF_OCPROJECT', value: project)]
        ],
    ]

    def results = pipelineUtils.runParallel(
        iqeUtils.prepareStages(appConfigs, cloud, "smoke", pytestMarker, false, false)
    )

    openShiftUtils.collectLogs(project: project)

    stage("Wipe test environment") {
        sh "ocdeployer wipe -l e2esmoke=true --no-confirm ${project}"
    }

    if (currentBuild.result != "SUCCESS" || results['failed'].size() > 0) {
        error("Smoke test failed");
    }
}


private def allocateResourcesAndRun(
    String refSpec, String ocDeployerBuilderPath, String ocDeployerComponentPath,
    String ocDeployerServiceSets, pytestMarker, List<String> iqePlugins, Map extraEnvVars,
    String configFileCredentialsId, int buildScaleFactor, int parallelWorkerCount,
    Boolean parallelBuild, String cloud, Boolean ui
) {
    // Reserve a smoke test project, spin up a slave pod, and run the test pipeline
    lock(label: pipelineVars.smokeTestResourceLabel, quantity: 1, variable: "PROJECT") {
        echo "Using project: ${env.PROJECT}"

        envVars = [envVar(key: 'ENV_FOR_DYNACONF', value: 'smoke')]
        parameters = [
            image: pipelineVars.iqeCoreImage,
            namespace: env.PROJECT,
            envVars: envVars,
            resourceLimitCpu: '1',
            resourceLimitMemory: '2Gi',
            cloud: cloud,
        ]
        openShiftUtils.withNodeSelector(parameters, ui) {
            runPipeline(
                refSpec, env.PROJECT, ocDeployerBuilderPath, ocDeployerComponentPath, 
                ocDeployerServiceSets, pytestMarker, iqePlugins, extraEnvVars,
                configFileCredentialsId, buildScaleFactor, parallelWorkerCount, parallelBuild,
                cloud, ui
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
    def buildScaleFactor = p.get('buildScaleFactor', 1)
    def parallelWorkerCount = p.get('parallelWorkerCount', 2)
    def parallelBuild = p.get('parallelBuild', false)
    def cloud = p.get('cloud', "openshift")
    def ui = p.get('ui', false)

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
                pytestMarker, iqePlugins, extraEnvVars, configFileCredentialsId, buildScaleFactor,
                parallelWorkerCount, parallelBuild, cloud, ui
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
            pytestMarker, iqePlugins, extraEnvVars, configFileCredentialsId, buildScaleFactor,
            parallelWorkerCount, parallelBuild, cloud, ui
        )
    }
}
