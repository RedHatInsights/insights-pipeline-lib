def runPluginTests(plugins, envName, markerName, slackChannel, slackUrl) {
    def lockName = "${env.JOB_NAME}-${envName}"
    def pluginNames = plugins.keySet()

    if (markerName != "") {
        lockName = lockName + "-${markerName}"
    }

    lock(lockName) {
        timeout(time: 150, unit: "MINUTES") {
            results = pipelineUtils.runParallel(prepareStages(pluginNames, envName, markerName))
        }
    }

    node {
        writeFile(
            file: "ibutsu.html",
            text: (
                "<a href=\"https://ibutsu.cloud.paas.psi.redhat.com/results?" +
                "metadata.jenkins.build_number[eq]=${env.BUILD_NUMBER}&metadata.jenkins.job_name[eq]=" +
                "${env.JOB_NAME}\">Ibutsu results</a>"
            )
        )

        archiveArtifacts "ibutsu.html"

        if (results['failed'].size() > 0) { 
            def failedPlugins = plugins.subMap(results['failed'])
            slackUtils.sendTestsFailedSlackMsg(failedPlugins, slackChannel, slackUrl)
            error("Tests did not pass")
        }
    }
}

def prepareStages(pluginNames, jobName, envName, markerName) {
    def stages = [:]
    for (pluginName in pluginNames) {
        // according to https://jenkins.io/doc/pipeline/examples/#parallel-multiple-nodes
        def plugin = pluginName
        stages[plugin] = {
            def markerOption = ""
            if (markerName != "") {
                markerOption = "-m ${markerName}"
            }
            envVars = [
                envVar(key: 'ENV_FOR_DYNACONF', value: envName),
                envVar(key: 'IQE_TESTS_LOCAL_CONF_PATH', value: '/tmp/settings_yaml'),
            ]
            openShiftUtils.withUINode(envVars: envVars, image: pipelineVars.iqeCoreImage) {
                if (!envName) error("No env specified")

                stage("Install iqe-${plugin}-plugin") {
                    sh "iqe plugin install red-hat-internal-envs"
                    sh "iqe plugin install ${plugin}"
                }

                stage("Inject credentials") {
                    withCredentials(
                        [file(credentialsId: "${envName}IQESettingsYaml", variable: "YAML_FILE")]
                    ) {
                        sh "mkdir -p \$IQE_TESTS_LOCAL_CONF_PATH"
                        sh "cp \$YAML_FILE \$IQE_TESTS_LOCAL_CONF_PATH"
                    }
                }

                stage("Run integration tests") {
                    try {
                        sh(
                            """
                            iqe tests plugin ${plugin} -v -s ${markerOption} \
                            --junitxml=${plugin}-junit.xml \
                            -o ibutsu_server=https://ibutsu-api.cloud.paas.psi.redhat.com \
                            -o ibutsu_source=${env.BUILD_TAG} \
                            --log-file=${plugin}-iqe.log
                            """.stripIndent()
                        )
                    } catch (err) {
                        throw err
                    } finally {
                        archiveArtifacts "${plugin}-iqe.log"
                        junit "${plugin}-junit.xml"
                    }
                }
            }
        }
    }
    return stages
}
