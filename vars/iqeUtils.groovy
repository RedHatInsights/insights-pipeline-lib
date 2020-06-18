import java.util.ArrayList


private def parseOptions(Map options) {
    /*
     * Take the options map provided by the caller in prepareStages and populate it with defaults
     * if needed
     */
    if (!options['envName']) error('envName must be defined')

    def envName = options['envName']

    options['image'] = options.get('image', pipelineVars.iqeCoreImage)
    options['cloud'] = options.get('cloud', pipelineVars.defaultCloud)
    options['marker'] = options.get('marker', envName)
    options['filter'] = options.get('filter', "")
    options['allocateNode'] = options.get('allocateNode', true)
    options['ibutsu'] = options.get('ibutsu', true)
    options['ibutsuUrl'] = options.get('ibutsuUrl', pipelineVars.defaultIbutsuUrl)
    options['ui'] = options.get('ui', true)
    options['settingsFromGit'] = options.get('settingsFromGit', false)
    options['settingsFileCredentialsId'] = options.get(
        'settingsFileCredentialsId', "${envName}IQESettingsYaml")
    options['settingsGitRepo'] = options.get(
        'settingsRepo', pipelineVars.jenkinsConfigRepo)
    options['settingsGitPath'] = options.get(
        'settingsGitPath', "configs/default-${envName}-settings.yaml")
    options['settingsGitBranch'] = options.get(
        'settingsGitBranch', "master")
    options['settingsGitCredentialsId'] = options.get(
        'settingsGitCredentialsId', pipelineVars.gitHttpCreds)
    options['parallelWorkerCount'] = options.get('parallelWorkerCount', 2)
    options['extraEnvVars'] = options.get('extraEnvVars', [:])
    options['iqeVaultEnabled'] = options.get('iqeVaultEnabled', false)
    options['iqeVaultUrl'] = options.get('iqeVaultUrl', pipelineVars.defaultVaultUrl)
    options['iqeVaultAppRoleCredentialsId'] = options.get(
        'iqeVaultAppRoleCredentialsId', pipelineVars.defaultVaultAppRoleIdCredential)
    options['iqeVaultTokenCredentialsId'] = options.get('iqeVaultTokenCredentialsId')
    options['iqeVaultVerify'] = options.get('iqeVaultVerify', true)
    options['iqeVaultAppRoleTokenCredentialsId'] = options.get(
        'iqeVaultAppRoleTokenCredentialsId', pipelineVars.defaultVaultAppRoleTokenCredential)
    options['iqeVaultMountPoint'] = options.get(
        'iqeVaultMountPoint', pipelineVars.defaultVaultMountPoint)

    return options
}


private def mergeAppOptions(Map options, Map appOptions) {
    if (!appOptions instanceof Map) {
        error("Incorrect syntax for appConfigs: 'options' for app is not a Map")
    }

    def mergedOptions = [:]

    options.each { key, defaultValue ->
        mergedOptions[key] = appOptions.get(key, defaultValue)
    }

    // Support compatibility w/ smoke test syntax which specifies markers as a list of strings
    if (mergedOptions['marker'] instanceof java.util.ArrayList) {
        mergedOptions['marker'] = mergedOptions['marker'].join(" or ")
    }

    return mergedOptions
}


def runIQE(String plugin, Map appOptions) {
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

    def filterArgs = ""
    def ibutsuArgs = ""

    if (appOptions['filter']) {
        filterArgs = "-k \"${appOptions['filter']}\""
    }

    if (appOptions['ibutsu']) {
        ibutsuArgs = "-o ibutsu_server=${appOptions['ibutsuUrl']} -o ibutsu_source=${env.BUILD_TAG}"
    }

    def marker = appOptions['marker']

    catchError(stageResult: "FAILURE") {
        // run parallel tests
        def markerArgs = marker ? "-m \"parallel and (${marker})\"" : "-m \"parallel\""
        // export the .env file to load env vars that should be present even before dynaconf is
        // invoked such as IQE_TESTS_LOCAL_CONF_PATH
        status = sh(
            script: (
                """
                set +x && export \$(cat "${env.WORKSPACE}/.env" | xargs) && set -x && \
                iqe tests plugin ${plugin} -s -v \
                --junitxml=junit-${plugin}-parallel.xml \
                ${markerArgs} \
                ${filterArgs} \
                -n ${appOptions['parallelWorkerCount']} \
                ${ibutsuArgs} \
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
            error("Parallel test run failed with exit code ${status}")
        }

        // run sequential tests
        markerArgs = marker ? "-m \"not parallel and (${marker})\"" : "-m \"not parallel\""
        // export the .env file to load env vars that should be present even before dynaconf is
        // invoked such as IQE_TESTS_LOCAL_CONF_PATH
        status = sh(
            script: (
                """
                set +x && export \$(cat "${env.WORKSPACE}/.env" | xargs) && set -x && \
                iqe tests plugin ${plugin} -s -v \
                --junitxml=junit-${plugin}-sequential.xml \
                ${markerArgs} \
                ${filterArgs} \
                ${ibutsuArgs} \
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
            error("Sequential test run hit an error with exit code ${status}")
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


private def getSettingsFromGit(
    String settingsGitRepo, String settingsGitPath, String settingsGitCredentialsId,
    String settingsGitBranch, String settingsDir
) {
    def repoDir = "${env.WORKSPACE}/settings_repo"
    sh "rm -fr \"${repoDir}\""

    gitUtils.checkOutRepo(
        targetDir: repoDir,
        repoUrl: settingsGitRepo,
        branch: settingsGitBranch,
        credentialsId: settingsGitCredentialsId
    )

    dir(repoDir) {
        sh "cp \"${settingsGitPath}\" \"${settingsDir}/settings.local.yaml\""
    }
}


private def getSettingsFromJenkinsSecret(String settingsFileCredentialsId, String settingsDir) {
    withCredentials(
        [file(credentialsId: settingsFileCredentialsId, variable: "YAML_FILE")]
    ) {
        sh "cp \$YAML_FILE \"${settingsDir}/settings.local.yaml\""
    }
}


private def writeEnvFromCredential(String key, String credentialsId) {
    withCredentials(
        [string(credentialsId: credentialsId, variable: "SECRET")]
    ) {
        sh "echo \"${key}=\$SECRET\" >> \"${env.WORKSPACE}/.env\""
    }
}


private def writeEnv(String key, String value) {
    sh "echo \"${key}=${value}\" >> \"${env.WORKSPACE}/.env\""
}


private def writeVaultEnvVars(Map options) {
    if (!options['iqeVaultEnabled']) return

    if (options['iqeVaultUrl']) writeEnv('DYNACONF_IQE_VAULT_URL', options['iqeVaultUrl'])

    // if a token is specified, use it, otherwise use an app role to authenticate
    if (options['iqeVaultTokenCredentialsId']) {
        writeEnvFromCredential('DYNACONF_IQE_VAULT_TOKEN', options['iqeVaultTokenCredentialsId'])
    } else {
        if (options['iqeVaultAppRoleTokenCredentialsId']) {
            writeEnvFromCredential(
                'DYNACONF_IQE_VAULT_APPROLE_TOKEN',
                options['iqeVaultAppRoleTokenCredentialsId']
            )
        }
        if (options['iqeVaultAppRoleCredentialsId']) {
            writeEnvFromCredential(
                'DYNACONF_IQE_VAULT_APPROLE',
                options['iqeVaultAppRoleCredentialsId']
            )
        }
    }

    writeEnv('DYNACONF_IQE_VAULT_VERIFY', options['iqeVaultVerify'].toString())
    writeEnv('DYNACONF_IQE_VAULT_MOUNT_POINT', options['iqeVaultMountPoint'])
    writeEnv('DYNACONF_IQE_VAULT_LOADER_ENABLED', options['iqeVaultEnabled'].toString())
}


private def configIQE(Map options) {
    /* Sets up the settings.local.yaml and .env files */
    def settingsDir = "${env.WORKSPACE}/iqe_local_settings"
    sh "rm -fr ${settingsDir}"
    sh "mkdir -p ${settingsDir}"

    if (options['settingsFromGit']) {
        getSettingsFromGit(
            options['settingsGitRepo'],
            options['settingsGitPath'],
            options['settingsGitCredentialsId'],
            options['settingsGitBranch'],
            settingsDir
        )
    }
    else if (options['settingsFileCredentialsId']) {
        getSettingsFromJenkinsSecret(options['settingsFileCredentialsId'], settingsDir)
    }

    sh "rm -f \"${env.WORKSPACE}/.env\""
    writeEnv('ENV_FOR_DYNACONF', options['envName'])
    writeEnv('IQE_TESTS_LOCAL_CONF_PATH', settingsDir)

    writeVaultEnvVars(options)
    options['extraEnvVars'].each { key, value ->
        writeEnv(key, value)
    }
}


private def createTestStages(Map appConfig) {
    def appOptions = appConfig['options']

    stage("Configure IQE") {
        configIQE(appOptions)
    }

    stage("Install red-hat-internal-envs plugin") {
        sh "iqe plugin install red-hat-internal-envs"
    }

    def pluginResults = [:]

    for (plugin in appConfig["plugins"]) {
        // Check if the plugin name was given in "iqe-NAME-plugin" format or just "NAME"
        // strip unnecessary whitespace first
        plugin = plugin.replaceAll("\\s", "")

        if (plugin ==~ /iqe-\S+-plugin.*/) plugin = plugin.replaceAll(/iqe-(\S+)-plugin/, '$1')

        stage("Install iqe-${plugin}-plugin") {
            sh "iqe plugin install ${plugin}"
        }

        stage("Run ${plugin} integration tests") {
            def result = runIQE(plugin, appOptions)
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
        else if (!pluginsPassed) {
            error "No plugins failed nor passed. Were the test runs aborted early?"
        }
    }
}


def prepareStages(Map defaultOptions, Map appConfigs) {
    /*
     * Given a Map of appConfigs and the kubernetes cloud name, env name, and pytest expression,
     * prepare a Map of stage closures that will be later run using 'parallel()' to execute tests
     * for multiple IQE plugins.
     *
     * For each app defined in the appConfig, the specified plugins will be installed
     * and tests will fire in sequential order. Tests for each 'app', however, run in parallel.
     *

     *
     * Example:
     *  ["app1": ["plugins": ["plugin1", "plugin2"], "ui": true]
     *   "app2": ["plugins": ["plugin3"], "ui": false, "settingsFileCredentialsId": "mySettings"]]
     *
     * @return Map with key = stage name, value = closure
     */
    def stages = [:]

    def options = parseOptions(defaultOptions)

    echo "options: ${options}"

    appConfigs.each{ k, v ->
        // re-define vars, see https://jenkins.io/doc/pipeline/examples/#parallel-multiple-nodes
        def app = k
        def appConfig = v

        if (!appConfig instanceof Map) {
            error("Incorrect syntax for appConfig: must be a Map")
        }

        def appOptions = mergeAppOptions(options, appConfig.get('options', [:]))
        appConfig['options'] = appOptions
        echo "appOptions: ${appOptions}"

        stages[app] = {
            if (appOptions['allocateNode']) {
                def withNodeParams = [
                    image: appOptions['image'],
                    cloud: appOptions['cloud'],
                ]
                openShiftUtils.withNodeSelector(withNodeParams, appOptions['ui']) {
                    createTestStages(appConfig)
                }
            }
            else {
                createTestStages(appConfig)
            }
        }
    }
    return stages
}
