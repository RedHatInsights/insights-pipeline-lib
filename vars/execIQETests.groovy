/*
 * This function will do the following:
 * - Create a Jenkins job, with checkbox parameters for each 'app' name, and a dropdown parameter
 *      for the listed envs
 * - When the job is built, it will run iqe tests for each "app" in parallel.
 * - Within each app, the given plugins will be installed and tests run in sequential order
 * - 'ui' indicates whether the tests require selenium, if so, a UI pod is spun up (default: false)
 * - 'settingsFileCredentialsId' sets the settings file secret to load for that plugin
 *      (if unset, uses "${envName}IQESettingsYaml")
 * - 'parallelWorkerCount' defines number of workers for pytest-xdist
 * - 'extraEnvVars' -- an optional list of envVar objects to set on the test container
 * - Return the parallel stage run results to the caller
 *
 * @param appConfigs Map with the following format:
 *      ["app1": ["plugins": ["plugin1", "plugin2"], "ui": true]
 *      "app2": ["plugins": ["plugin3"], "ui": false, "settingsFileCredentialsId": "mySettings"]]
 * @param envs String[] of env names
 * @param marker String with default marker expression (optional, if blank "envName" is used)
 *
 * returns Map with format ["success": String[] successStages, "failed": String[] failedStages]
 */
def call(args = [:]) {
    def appConfigs = args['appConfigs']
    def envs = args['envs']
    def cloud = args.get('cloud', pipelineVars.upshiftCloud)
    def defaultMarker = args.get('defaultMarker')

    p = []
    // Add a param option for simply reloading this job
    p.add(
        [
            $class: 'BooleanParameterDefinition',
            name: "reload", defaultValue: false, description: "Reload the job's config and quit"
        ]
    )

    // Add a dropdown to select env
    p.add(
        [
            $class: "ChoiceParameterDefinition",
            name: "env",
            choices: envs.join("\n"),
            description: "The target environment to run tests against"
        ]
    )

    // Add checkboxes for each app name, checked by default
    appConfigs.each { appName, appConfig ->
        p.add(
            [
                $class: 'BooleanParameterDefinition',
                name: appName, defaultValue: true, description: "Run tests for ${appName}"
            ]
        )
    }

    // Add text field for test markers
    p.add(
        [
            $class: 'StringParameterDefinition',
            name: "marker", defaultValue: defaultMarker ? defaultMarker : "",
            description: "Enter pytest marker expression, leave blank to use '<envName>'"
        ]
    )

    properties([parameters(p)])

    // Exit the job if the "reload" box was checked
    if (params.reload) {
        echo "Job is only reloading"
        currentBuild.description = "reload"
        return
    }

    // For build #1, only load the pipeline and exit
    // This is so the next time the job is built, "Build with parameters" will be available
    if (env.BUILD_NUMBER.toString() == "1") {
        echo "Initial run, loaded pipeline job and now exiting."
        currentBuild.description = "loaded params"
        return
    }

    // Run the tests
    lock("${params.env}-test") {
        timeout(time: 150, unit: "MINUTES") {
            results = pipelineUtils.runParallel(prepareStages(appConfigs, cloud))
        }
    }

    // archive an artifact containing the ibutsu URL for this run
    node {
        writeFile(
            file: "ibutsu.html",
            text: (
                "<a href=\"https://ibutsu.cloud.paas.psi.redhat.com/results?" +
                "metadata.jenkins.build_number=${env.BUILD_NUMBER}&metadata.jenkins.job_name=" +
                "${env.JOB_NAME}\">Click here</a>"
            )
        )

        archiveArtifacts "ibutsu.html"

    }

    return results
}


def withNodeSelector(Map parameters = [:], Boolean ui, Closure body) {
    /* A wrapper that selects a different closure based on if 'ui' is true or false */
    if (ui) {
        openShiftUtils.withUINode(parameters) {
            body()
        }
    } else {
        openShiftUtils.withNode(parameters) {
            body()
        }   
    }
}


def runIQE(plugin, marker, parallelWorkerCount) {
    /*
     * Run IQE sequential tests and parallel tests for a plugin.
     *
     * If an IQE run fails, then fail the stage. Ignore pytest failing for having 0 tests collected
     *
     * Returns result of "SUCCESS" or "FAILURE"
     */
    def result = "SUCCESS"
    def status
    catchError(stageResult: "FAILURE") {
        // run parallel tests
        status = sh(
            script: (
                """
                iqe tests plugin ${plugin} -s -v \
                --junitxml=junit-${plugin}-parallel.xml \
                -m "parallel and (${marker})" -n ${parallelWorkerCount} \
                -o ibutsu_server=https://ibutsu-api.cloud.paas.psi.redhat.com \
                -o ibutsu_source=${env.BUILD_TAG} \
                --log-file=iqe-${plugin}-parallel.log --log-file-level=DEBUG 2>&1 \
                """.stripIndent()
            ),
            returnStatus: true
        )
        // status code 5 means no tests collected, ignore this error.
        if (status > 0 && status != 5) {
            error("Parallel test run failed")
            result = "FAILURE"
        }

        // run sequential tests
        status = sh(
            script: (
                """
                iqe tests plugin ${plugin} -s -v \
                --junitxml=junit-${plugin}-sequential.xml \
                -m "not parallel and (${marker})" \
                -o ibutsu_server=https://ibutsu-api.cloud.paas.psi.redhat.com \
                -o ibutsu_source=${env.BUILD_TAG} \
                --log-file=iqe-${plugin}-sequential.log --log-file-level=DEBUG 2>&1 \
                """.stripIndent()
            ),
            returnStatus: true
        )
        // status code 5 means no tests collected, ignore this error.
        if (status > 0 && status != 5) {
            error("Sequential test run hit an error")
            result = "FAILURE"
        }
    }

    catchError {
        archiveArtifacts "iqe-*.log"
    }

    return result
}


def prepareStages(Map appConfigs, String cloud) {
    def stages = [:]
    def envName = params.env
    def marker = params.marker ? "${params.marker}" : "${envName}"

    appConfigs.each{ k, v ->
        // re-define vars, see https://jenkins.io/doc/pipeline/examples/#parallel-multiple-nodes
        def app = k
        def appConfig = v
        def plugins = appConfig['plugins']
        def ui = appConfig.get('ui', false)
        def parallelWorkerCount = appConfig.get('parallelWorkerCount', 2)
        def extraEnvVars = appConfig.get('extraEnvVars', [])
        def settingsFileCredentialsId = appConfig.get(
            'settingsFileCredentialsId', "${envName}IQESettingsYaml"
        )
        def pluginResults = [:]

        stages[app] = {
            envVars = [
                envVar(key: 'ENV_FOR_DYNACONF', value: envName),
                envVar(key: 'IQE_TESTS_LOCAL_CONF_PATH', value: '/tmp/settings_yaml'),
            ]
            envVars.addAll(extraEnvVars)

            def withNodeParams = [
                envVars: envVars,
                //image: pipelineVars.iqeCoreImage,
                image: "docker-registry.default.svc:5000/insights-qe-ci/iqe-core",
                cloud: cloud,
            ]
            withNodeSelector(withNodeParams, ui) {
                if (!envName) error("No env specified")

                stage("Inject credentials") {
                    withCredentials(
                        [file(credentialsId: settingsFileCredentialsId, variable: "YAML_FILE")]
                    ) {
                        sh "mkdir -p \$IQE_TESTS_LOCAL_CONF_PATH"
                        sh "cp \$YAML_FILE \$IQE_TESTS_LOCAL_CONF_PATH"
                    }
                }
                stage("Install red-hat-internal-envs plugin") {
                    sh "iqe plugin install red-hat-internal-envs"
                }
                for (plugin in appConfig["plugins"]) {
                    stage("Install iqe-${plugin}-plugin") {
                        sh "iqe plugin install ${plugin}"
                    }

                    stage("Run ${plugin} integration tests") {
                        def result = runIQE(plugin, marker, parallelWorkerCount)
                        pluginResults[plugin] = result
                    }
                }

                stage("Results") {
                    // if no plugins ran any tests, this junit step will fail
                    junit "junit-*.xml"

                    def pluginsFailed = pluginResults.findAll { it.value == "FAILURE" }
                    def pluginsPassed = pluginResults.findAll { it.value == "SUCCESS" }

                    echo "Plugins passed: ${pluginsPassed.keySet().join(",")}"
                    if (pluginsFailed) {
                        error "Plugins failed: ${pluginsFailed.keySet().join(",")}"
                    }
                }
            }
        }
    }
    return stages
}
