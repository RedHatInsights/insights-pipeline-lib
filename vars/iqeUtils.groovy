def runIQE(plugin, marker, parallelWorkerCount) {
    /*
     * Run IQE sequential tests and parallel tests for a plugin.
     *
     * If an IQE run fails, then fail the stage. Ignore pytest failing for having 0 tests collected
     *
     * Returns result of "SUCCESS" or "FAILURE"
     */
    def result
    def status
    def noTests

    if (marker instanceof Array) {
        marker = marker.join(" or ")
    }

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

        // status code 5 means no tests collected
        if (status == 5) {
            noTests = true
        }
        else if (status > 0) {
            result = "FAILURE"
            error("Parallel test run failed")
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

        // status code 5 means no tests collected
        if (status == 5) {
            if (noTests) {
                // the parallel run had no results, and so did the sequential run. Fail this plugin.
                result = "FAILURE"
                error("Tests produced no results")
            }
        }
        else if (status > 0) {
            result = "FAILURE"
            error("Sequential test run hit an error")
        }
        else {
            result = "SUCCESS"
        }
    }

    catchError {
        archiveArtifacts "iqe-${plugin}-*.log"
        junit "junit-${plugin}-*.xml"
    }

    return result
}


def prepareStages(Map appConfigs, String cloud, String envName, marker) {
    /*
     * Given a Map of appConfigs and the kubernetes cloud name, env name, and pytest expression,
     * prepare a Map of stage closures that will be later run using 'parallel()' to execute tests
     * for multiple IQE plugins.
     *
     * For each app defined in the appConfig, the specified plugins will be installed
     * and tests will fire in sequential order. Tests for each 'app', however, run in parallel.
     *
     * @param appConfigs -- Map with keys = app name, values a Map with config for that app
     * - 'ui' indicates whether the tests require selenium, if so, a UI pod is spun up (default: false)
     * - 'settingsFileCredentialsId' sets the settings file secret to load for that plugin
     *      (if unset, uses "${envName}IQESettingsYaml")
     * - 'parallelWorkerCount' defines number of workers for pytest-xdist
     * - 'extraEnvVars' -- an optional list of envVar objects to set on the test container
     *
     * Example:
     *  ["app1": ["plugins": ["plugin1", "plugin2"], "ui": true]
     *   "app2": ["plugins": ["plugin3"], "ui": false, "settingsFileCredentialsId": "mySettings"]]
     *
     * @param cloud String -- name of the kubernetes plugin cloud to use
     * @param envName String -- name of IQE environment
     * @param marker String or String[] -- pytest marker expression(s)
     * @return Map with format ["success": String[] successStages, "failed": String[] failedStages]
     */
    def stages = [:]
    def marker = marker ? marker : envName

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
            openShiftUtils.withNodeSelector(withNodeParams, ui) {
                if (!envName) error("No env specified")

                stage("Inject credentials") {
                    withCredentials(
                        [file(credentialsId: settingsFileCredentialsId, variable: "YAML_FILE")]
                    ) {
                        sh "mkdir -p \$IQE_TESTS_LOCAL_CONF_PATH"
                        sh "cp \$YAML_FILE \$IQE_TESTS_LOCAL_CONF_PATH/settings.local.yaml"
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
