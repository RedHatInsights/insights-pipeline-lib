/*
 * This function will do the following:
 * - Create a Jenkins job, with checkbox parameters for each 'app' name, and a dropdown parameter
 *      for the listed envs
 * - When the job is built, it will run iqe tests for each "app" in parallel.
 * - Within each app, the given plugins will be installed and tests run in sequential order
 * - 'ui' indicates whether the tests require selenium, if so, a UI pod is spun up (default: false)
 * - Return the parallel stage run results to the caller
 *
 * @param appConfigs Map with the following format:
 *      ["app1": ["plugins": ["plugin1", "plugin2"], "ui": true]
 *      "app2": ["plugins": ["plugin3"], "ui": false]]
 * @param envs String[] of env names
 * @param marker String with default marker expression (optional, if blank "envName" is used)
 *
 * returns Map with format ["success": String[] successStages, "failed": String[] failedStages]
 */
def call(args = [:]) {
    def appConfigs = args['appConfigs']
    def envs = args['envs']
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
            results = pipelineUtils.runParallel(prepareStages(appConfigs))
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


def prepareStages(appConfigs) {
    def stages = [:]
    def envName = params.env
    def markerArg = params.marker ? "-m \"${params.marker}\"" : "-m ${envName}"

    appConfigs.each{ k, v ->
        // re-define vars, see https://jenkins.io/doc/pipeline/examples/#parallel-multiple-nodes
        def app = k
        def appConfig = v
        def plugins = appConfig['plugins']
        def ui = appConfig.get('ui', false)
        def pluginErrors = [:]

        stages[app] = {
            envVars = [
                envVar(key: 'ENV_FOR_DYNACONF', value: envName),
                envVar(key: 'IQE_TESTS_LOCAL_CONF_PATH', value: '/tmp/settings_yaml'),
            ]
            def withNodeFunc = ui ? openShiftUtils.withUINode : openShiftUtils.withNode
            withNodeFunc(envVars: envVars, image: pipelineVars.iqeCoreImage) {
                if (!envName) error("No env specified")

                stage("Inject credentials") {
                    withCredentials(
                        [file(credentialsId: "${envName}IQESettingsYaml", variable: "YAML_FILE")]
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
                        try {
                            sh(
                                """
                                iqe tests plugin ${plugin} -v -s ${markerArg} \
                                --junitxml=${plugin}-junit.xml \
                                -o ibutsu_server=https://ibutsu-api.cloud.paas.psi.redhat.com \
                                -o ibutsu_source=${env.BUILD_TAG} \
                                --log-file=${plugin}-iqe.log
                                """.stripIndent()
                            )
                            pluginErrors[plugin] = null  // null == passed
                        } catch (err) {
                            pluginErrors[plugin] = err.getMessage()
                        } finally {
                            archiveArtifacts "${plugin}-iqe.log"
                            junit "${plugin}-junit.xml"
                        }
                    }
                }

                stage("Check plugin results") {
                    def pluginsFailed = pluginErrors.findAll { it.value != null }
                    def pluginsPassed = pluginErrors.findAll { it.value == null }

                    echo "Plugins passed: ${pluginsPassed.keySet().join(",")}"
                    if (pluginsFailed) {
                        pluginsFailed.each { name, reason ->
                            echo "Plugin failed: ${name} reason: ${reason}"
                        }
                        error "Plugins failed: ${pluginsFailed.keySet().join(",")}"
                    }
                }
            }
        }
    }
    return stages
}
