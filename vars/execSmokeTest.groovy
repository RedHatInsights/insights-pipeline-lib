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
    ocDeployerEnv, buildScaleFactor, deployScaleFactor, scaleFirstSetOnly, parallelBuild
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
            "-e ${ocDeployerEnv} ${project} --scale-resources ${buildScaleFactor} " +
            "--secrets-src-project secrets"
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
    def factor = deployScaleFactor
    ocDeployerServiceSets.split(',').eachWithIndex { serviceSet, i ->
        def set = serviceSet // https://jenkins.io/doc/pipeline/examples/#parallel-multiple-nodes
        if (scaleFirstSetOnly) {
            factor = i == 0 ? deployScaleFactor : 1
        }
        deployTasks["Deploy ${serviceSet}"] = {
            sh(
                "ocdeployer deploy -w -f -l e2esmoke=true -s ${set} " +
                "-e custom-env -e ${ocDeployerEnv} ${project} " +
                "--scale-resources ${factor} --secrets-src-project secrets"
            )
        }
    }

    // Run the service deployments in parallel
    parallel(deployTasks)
}


private def wipeNamespace(project) {
    // wipe all resources that have label 'e2esmoke=true'
    stage("Wipe test environment") {
        sh "ocdeployer wipe -l e2esmoke=true --no-confirm ${project}"
    }
}


private def runDeployStages(
    refSpec, project, ocDeployerBuilderPath, ocDeployerComponentPath,
    ocDeployerServiceSets, ocDeployerEnv, buildScaleFactor, deployScaleFactor,
    scaleFirstSetOnly, parallelBuild
) {
    // check out e2e-deploy
    stage("Check out e2e-deploy") {
        gitUtils.checkOutRepo(
            targetDir: pipelineVars.e2eDeployDir,
            repoUrl: pipelineVars.e2eDeployRepo,
            credentialsId: "InsightsDroidGitHubHTTP"
        )
        dir(pipelineVars.e2eDeployDir) {
            sh "pip install -r requirements.txt"
        }
    }

    wipeNamespace(project)

    try {
        dir(pipelineVars.e2eDeployDir) {
            deployEnvironment(
                refSpec, env.PROJECT, ocDeployerBuilderPath, ocDeployerComponentPath,
                ocDeployerServiceSets, ocDeployerEnv, buildScaleFactor, deployScaleFactor,
                scaleFirstSetOnly, parallelBuild
            )
        }
        return true
    } catch (err) {
        echo("Hit error during deploy!")
        echo(err.toString())
        return false
    }
}


private def runPipeline(
    refSpec, ocDeployerBuilderPath, ocDeployerComponentPath, ocDeployerServiceSets, ocDeployerEnv,
    buildScaleFactor, deployScaleFactor, scaleFirstSetOnly, parallelBuild, options, appConfigs
) {
    def results
    // Reserve a smoke test project, spin up a slave pod, and run the test pipeline
    lock(label: pipelineVars.smokeTestResourceLabel, quantity: 1, variable: "PROJECT") {
        pipelineUtils.cancelPriorBuilds()
        currentBuild.result = "SUCCESS"

        def project = env.PROJECT
        echo "Using project: ${project}"
        options['extraEnvVars']['DYNACONF_OCPROJECT'] = project
        options['namespace'] = project

        def parameters = [
            image: pipelineVars.iqeCoreImage,
            namespace: project,
            resourceLimitCpu: '1',
            resourceLimitMemory: '2Gi',
            cloud: options['cloud'],
        ]

        openShiftUtils.withNode(parameters) {
            def deployed = runDeployStages(
                refSpec, project, ocDeployerBuilderPath, ocDeployerComponentPath,
                ocDeployerServiceSets, ocDeployerEnv, buildScaleFactor, deployScaleFactor,
                scaleFirstSetOnly, parallelBuild
            )

            if (deployed) {
                results = pipelineUtils.runParallel(iqeUtils.prepareStages(options, appConfigs))
            }

            if (!deployed || results['failed']) {
                stage("Collecting logs") {
                    openShiftUtils.collectLogs(project: project)
                }
            }

            wipeNamespace(project)
        }

        stage("Final result") {
            if (!results) error("Found no test results")
            def totalResults = results['success'].size() + results['failed'].size()
            if (totalResults != appConfigs.keySet().size()) error("Did not find test results for expected number of apps")

            if (currentBuild.result != "SUCCESS" || results['failed'].size() > 0) {
                error("Smoke test failed");
            }
        }
    }

    return results
}


private def setParamDefaults(refSpec, pytestMarker, pytestFilter, extraJobProperties) {
    if (pytestMarker instanceof java.util.ArrayList) {
        pytestMarker = pytestMarker.join(" or ")
    }

    def jobProperties = [
        parameters(
            [
                string(
                    name: 'GIT_REF',
                    defaultValue: refSpec,
                    description: 'The git ref to deploy for this app during the smoke test'
                ),
                string(
                    name: "MARKER",
                    defaultValue: pytestMarker ? pytestMarker : "",
                    description: "Enter pytest marker expression (-m), leave blank for none"
                ),
                string(
                    name: "FILTER",
                    defaultValue: pytestFilter ? pytestFilter : "",
                    description: "Enter pytest filter expression (-k), leave blank for none"
                ),
                booleanParam(
                    name: "RELOAD",
                    defaultValue: false,
                    description: "Reload this job's pipeline file and quit"
                )
            ]
        )
    ]

    jobProperties.addAll(extraJobProperties)
    properties(jobProperties)
}


def call(p = [:]) {
    /*
    * This function will do the following:
    * - Create a Jenkins job, with parameters for GIT_REF, MARKER, FILTER, and RELOAD
    * - Reserve a smoke test namespace and run ocdeployer to set up the ephemeral test env
    * - Run IQE
    * - Return the parallel stage run results to the caller
    *
    * @param appConfigs Map -- see iqeUtils
    * @param options Map -- see iqeUtils
    * @param defaultMarker String with default marker expression (optional, if blank "envName" is used)
    * @param defaultFilter String for default pytest filter expression (optional)
    * @param buildScaleFactor float -- scales the build config cpu/mem reservations by this amount
    * @param deployScaleFactor float -- scales the deploy config cpu/mem reservations by this amount
    * @param scaleFirstSetOnly bool -- scale only first content set by deployScaleFactor amount
    * @param parallelBuild -- if true, deploys build config at same time as the apps (default: false)
    * @param ocdeployerBuilderPath -- the ocdeployer "path" to the buildconfig template (e.g. buildfactory/myapp)
    * @param ocdeployerComponentPath -- the ocdeployer "path" to the app template (e.g. templates/myapp)
    * @param ocdeployerServiceSets -- the ocdeployer service sets to deploy into the ephemeral env
    * @param ocdeployerEnv -- the value to pass to ocdeployer's "--env" option (default: smoke)
    * @param extraJobProperties Array of job properties to append to the properties that this function
    *      creates (optional)
    *
    * @returns Map with format ["success": String[] successStages, "failed": String[] failedStages]
    * @throws AbortException if the 'RELOAD' parameter is true
    */

    // these args are the "new preferred" args to use with this job
    def ocDeployerBuilderPath = p['ocDeployerBuilderPath']
    def ocDeployerComponentPath = p['ocDeployerComponentPath']
    def ocDeployerServiceSets = p['ocDeployerServiceSets']
    def ocDeployerEnv = p.get('ocDeployerEnv', "smoke")
    def buildScaleFactor = p.get('buildScaleFactor', 1)
    def deployScaleFactor = p.get('deployScaleFactor', 1)
    def scaleFirstSetOnly = p.get('scaleFirstSetOnly', false)
    def parallelBuild = p.get('parallelBuild', false)
    def defaultMarker = p.get('defaultMarker')
    def defaultFilter = p.get('defaultFilter')
    def appConfigs = p.get('appConfigs', [:])
    def options = p.get('options', [:])
    def extraJobProperties = p.get('extraJobProperties', [])

    // these args are deprecated and provided for backward compatibility
    def pytestMarker = p.get('pytestMarker')
    def pytestFilter = p.get('pytestFilter')
    def iqePlugins = p.get('iqePlugins')
    def extraEnvVars = p.get('extraEnvVars', [:])
    def configFileCredentialsId = p.get('configFileCredentialsId', "")
    def parallelWorkerCount = p.get('parallelWorkerCount', 2)
    def cloud = p.get('cloud', "openshift")
    def ui = p.get('ui', false)

    def refSpec
    if (env.CHANGE_ID) refSpec = getRefSpec()
    else refSpec = env.BRANCH_NAME ? env.BRANCH_NAME : "master"

    // add job parameters to allow users to change job options when clicking 'build'
    setParamDefaults(
        refSpec,
        defaultMarker ? defaultMarker : pytestMarker,
        defaultFilter ? defaultFilter : pytestFilter,
        extraJobProperties
    )

    pipelineUtils.checkForReload()

    if (!params.GIT_REF) {
        echo "No git ref specified, is this the first time the job has run?"
        currentBuild.description = "reload"
        return
    }

    // Re-read the values from params incase they were changed by the user when clicking "build"
    refSpec = params.GIT_REF
    // set the iqeUtils options based on the args passed into the job
    options['marker'] = params.MARKER
    options['filter'] = params.FILTER
    options['envName'] = options.get('envName', "smoke")
    options['extraEnvVars'] = options.get("extraEnvVars", extraEnvVars)
    options['ibutsu'] = options.get('ibutsu')
    options['settingsFileCredentialsId'] = options.get(
        "settingsFileCredentialsId", configFileCredentialsId
    )
    options['parallelWorkerCount'] = options.get("parallelWorkerCount", parallelWorkerCount)
    options['cloud'] = options.get("cloud", cloud)
    options['ui'] = options.get("ui", ui)

    // create the appConfig/options used by iqeUtils if it was not specified (for backward compat)
    if (!appConfigs) {
        appConfigs = [
            smoke: [plugins: iqePlugins]
        ]
    }

    // If testing via a PR webhook trigger
    if (env.CHANGE_ID) {
        // Set the 'stable' label on the PR
        try {
            if (env.CHANGE_TARGET == 'stable') pullRequest.addLabels(['stable'])
        } catch (err) {
            echo "Failed to set 'stable' label: ${err.getMessage()}}"
        }

        // Run the job using github status notifications so the test status is reported to the PR
        gitUtils.withStatusContext("e2e-smoke") {
            runPipeline(
                refSpec, ocDeployerBuilderPath, ocDeployerComponentPath, ocDeployerServiceSets,
                ocDeployerEnv, buildScaleFactor, deployScaleFactor, scaleFirstSetOnly, parallelBuild,
                options, appConfigs
            )
        }
    // If testing via a manual trigger... we have no PR, so don't notify github/try to add PR label
    } else {
        runPipeline(
            refSpec, ocDeployerBuilderPath, ocDeployerComponentPath, ocDeployerServiceSets,
            ocDeployerEnv, buildScaleFactor, deployScaleFactor, scaleFirstSetOnly, parallelBuild,
            options, appConfigs
        )
    }
}
