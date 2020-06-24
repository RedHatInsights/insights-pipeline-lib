/*
 * Helper methods used for running iqe tests
 *
 * Most users will call the 'prepareStages' method. This sets up a Map containing closures that
 * can be used to run iqe tests in parallel with the specified appConfigs and options.
 *
 * Example:
 *     results = pipelineUtils.runParallel(iqeUtils.prepareStages(options, appConfigs))
 *
 * 'results' will be a Map with two keys: 'success' and 'failed'. Each key contains a list of which
 * parallel stage failed.
 *
 * OPTIONS
 * -------
 * For information on the syntax of 'options', see the 'parseOptions' method below.
 *
 * APPCONFIGS
 * ----------
 * appConfigs is a Map of keys = arbitrary name of app, values = a Map with 'plugins' and 'options'
 *
 * 'plugins' is a list of the iqe plugins that tests will run for. Tests for each plugin in this
 * list will be run sequentially (but each app will run in parallel). Any tests marked with the
 * "parallel" marker will be executed with pytest-xdist, and all other tests will be executed with a
 * single pytest process. The results from the parallel and sequential run are then merged.
 *
 * 'options' is a Map that can be used to override the 'options' passed into prepareStages for a
 * particular app.
 *
 * Example:
 *     def options = [ui: false, envName: "my_env"]
 *     def appConfigs = [app1: [plugins: ["plugin1", "plugin2"], options: [ui: true]],
 *                       app2: [plugins: ["plugin3"], options: [envName: "my_other_env"]]]
 *     def results = pipelineUtils.runParallel(iqeUtils.prepareStages(options, appConfigs))
 */


import java.util.ArrayList


private def parseOptions(Map options) {
    /*
     * Take the options map provided by the caller in prepareStages and populate it with defaults
     * if needed
     */
    if (!options['envName']) error('envName must be defined')

    // the ENV_FOR_DYNACONF environment name
    def envName = options['envName']

    // the container image that the tests will run with in OpenShift
    options['image'] = options.get('image', pipelineVars.iqeCoreImage)

    // the name of the 'cloud' under the Jenkins kubernetes plugin settings
    options['cloud'] = options.get('cloud', pipelineVars.defaultCloud)

    // the pytest marker expression (-m) used when running tests
    options['marker'] = options.get('marker', envName)

    // the pytest filter expression (-k) used when running tests
    options['filter'] = options.get('filter', "")

    // whether or not to spin up a jenkins pod for running the tests
    options['allocateNode'] = options.get('allocateNode', true)

    // whether or not to report results to ibutsu
    options['ibutsu'] = options.get('ibutsu', true)

    // the URL of ibutsu
    options['ibutsuUrl'] = options.get('ibutsuUrl', pipelineVars.defaultIbutsuUrl)

    // whether or not to provision a selenium container in the test pod
    options['ui'] = options.get('ui', true)

    // whether or not to load the IQE settings file from a git repo
    options['settingsFromGit'] = options.get('settingsFromGit', false)

    // if loading settings from a Jenkins file credential, the name of the credential
    options['settingsFileCredentialsId'] = options.get(
        'settingsFileCredentialsId', "${envName}IQESettingsYaml")

    // if loading settings from git, the repo URL
    options['settingsGitRepo'] = options.get('settingsRepo', pipelineVars.jenkinsConfigRepo)

    // if loading settings from git, the path in the repo to the config file
    options['settingsGitPath'] = options.get(
        'settingsGitPath', "configs/default-${envName}-settings.yaml")

    // if loading settings from git, the repo branch
    options['settingsGitBranch'] = options.get('settingsGitBranch', "master")

    // if loading settings from git, the Jenkins credentials ID for github authentication
    options['settingsGitCredentialsId'] = options.get(
        'settingsGitCredentialsId', pipelineVars.gitHttpCreds)

    // number of pytest-xdist workers to use for parallel tests
    options['parallelWorkerCount'] = options.get('parallelWorkerCount', 2)

    // a Map of additional env vars to set in the .env file before running iqe
    options['extraEnvVars'] = options.get('extraEnvVars', [:])

    // whether or not to use IQE's vault loader for importing secrets listed in the config file
    options['vaultEnabled'] = options.get('vaultEnabled', false)

    // if using vault, the URL of the vault server
    options['vaultUrl'] = options.get('vaultUrl', pipelineVars.defaultVaultUrl)

    // if using vault, the Jenkins credential ID that holds the vault AppRole role ID
    options['vaultRoleIdCredential'] = options.get(
        'vaultRoleIdCredential', pipelineVars.defaultVaultRoleIdCredential)

    // if using vault, the Jenkins credential ID that holds the vault AppRole secret ID
    options['vaultSecretIdCredential'] = options.get(
        'vaultSecretIdCredential', pipelineVars.defaultVaultSecretIdCredential)

    // if using vault, and not using approle, the Jenkins credential ID that holds the vault token
    options['vaultTokenCredential'] = options.get('vaultTokenCredential')

    // if using vault, whether or not to verify SSL connections
    options['vaultVerify'] = options.get('vaultVerify', true)

    // if using vault, the vault mount point for the kv engine
    options['vaultMountPoint'] = options.get('vaultMountPoint', pipelineVars.defaultVaultMountPoint)

    return options
}


private def mergeAppOptions(Map options, Map appOptions) {
    /* Merge an app's options with the default options */
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
                --log-file=iqe-${plugin}-parallel.log 2>&1 \
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
                --log-file=iqe-${plugin}-sequential.log 2>&1 \
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
    /* Download the IQE settings file from a git repo */
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
    /* Load the IQE settings file from a jenkins file credentials */
    withCredentials(
        [file(credentialsId: settingsFileCredentialsId, variable: "YAML_FILE")]
    ) {
        sh "cp \$YAML_FILE \"${settingsDir}/settings.local.yaml\""
    }
}


private def writeEnvFromCredential(String key, String credentialsId) {
    /* Helper to write a secret value to the .env file */
    withCredentials(
        [string(credentialsId: credentialsId, variable: "SECRET")]
    ) {
        sh "echo \"${key}=\$SECRET\" >> \"${env.WORKSPACE}/.env\""
    }
}


private def writeEnv(String key, String value) {
    /* Helper to write a String env value to the .env file */
    sh "echo \"${key}=${value}\" >> \"${env.WORKSPACE}/.env\""
}


private def writeVaultEnvVars(Map options) {
    /* Parse options for vault settings and write the vault env vars to the .env file */
    if (!options['vaultEnabled']) return

    if (options['vaultUrl']) writeEnv('DYNACONF_IQE_VAULT_URL', options['vaultUrl'])

    // if a token is specified, use it, otherwise use an app role to authenticate
    if (options['vaultTokenCredential']) {
        writeEnvFromCredential('DYNACONF_IQE_VAULT_TOKEN', options['vaultTokenCredential'])
    } else {
        if (options['vaultRoleIdCredential']) {
            writeEnvFromCredential(
                'DYNACONF_IQE_VAULT_ROLE_ID',
                options['vaultRoleIdCredential']
            )
        }
        if (options['vaultSecretIdCredential']) {
            writeEnvFromCredential(
                'DYNACONF_IQE_VAULT_SECRET_ID',
                options['vaultSecretIdCredential']
            )
        }
    }

    writeEnv('DYNACONF_IQE_VAULT_VERIFY', options['vaultVerify'].toString())
    writeEnv('DYNACONF_IQE_VAULT_MOUNT_POINT', options['vaultMountPoint'])
    writeEnv('DYNACONF_IQE_VAULT_LOADER_ENABLED', options['vaultEnabled'].toString())
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
     * Given a Map of defaultOptions (see parseOptions above) and appConfigs, prepare a Map of
     * stage closures that will be later run using 'parallel()' to execute tests for multiple IQE
     * plugins.
     *
     * For each app defined in the appConfig, the specified plugins will be installed
     * and tests will fire in sequential order. Tests for each 'app', however, run in parallel.
     *
     * See comment at the top of this file for description of options/appConfigs
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
