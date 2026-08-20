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

private Map parseOptions(Map options) {
    /*
     * Take the options map provided by the caller in prepareStages and populate it with defaults
     * if needed
     */
    if (!options['envName']) {
        error('envName must be defined')
    }

    // the container image that the tests will run with in OpenShift
    options['image'] = options.get('image') ?: pipelineVars.iqeCoreImage

    // the namespace that the test pods run in
    options['namespace'] = options.get('namespace')

    // the name of the 'cloud' under the Jenkins kubernetes plugin settings
    options['cloud'] = options.get('cloud', pipelineVars.defaultCloud)

    // the pytest marker expression (-m) used when running tests
    options['marker'] = options.get('marker', pipelineVars.defaultMarker)

    // the pytest filter expression (-k) used when running tests
    options['filter'] = options.get('filter', '')

    // the iqe --requirements expression used when running tests
    options['requirements'] = options.get('requirements', '')

    // the iqe --requirements-priority expression used when running tests
    options['requirementsPriority'] = options.get('requirementsPriority', '')

    // the iqe --test-importance filter expression used when running tests
    options['testImportance'] = options.get('testImportance', '')

    // whether or not to spin up a jenkins pod for running the tests
    options['allocateNode'] = options.get('allocateNode', true)

    // whether or not to report results to reportportal
    options['reportportal'] = options.get('reportportal', false)

    // whether or not to report results to ibutsu
    options['ibutsu'] = options.get('ibutsu', true)

    // the URL of ibutsu
    options['ibutsuUrl'] = options.get('ibutsuUrl', pipelineVars.defaultIbutsuUrl)

    // AWS bucket name for Ibutsu if S3 upload mode is used
    options['ibutsuBucket'] = options.get('ibutsuAwsBucket', pipelineVars.defaultIbutsuAwsBucket)

    // AWS region for Ibutsu if S3 upload mode is used
    options['ibutsuRegion'] = options.get('ibutsuAwsRegion', pipelineVars.defaultIbutsuAwsRegion)

    // whether or not to provision a playwright container in the test pod for UI tests
    options['ui'] = options.get('ui', false)

    // enable pytest-xdist plugin for multiprocess parallelism
    options['xdistEnabled'] = options.get('xdistEnabled', false)

    // number of pytest-xdist workers to use for parallel tests
    options['parallelWorkerCount'] = options.get('parallelWorkerCount', 2)

    // a Map of additional env vars to set in the .env file before running iqe
    Map extraEnvVars = options.get('extraEnvVars', [:])
    // if we are running UI tests, force IQE to use default browser
    if (options['ui'] && !extraEnvVars.containsKey('DYNACONF_MAIN__use_browser')) {
        extraEnvVars['DYNACONF_MAIN__use_browser'] = pipelineVars.defaultBrowser
    }
    options['extraEnvVars'] = extraEnvVars

    // parsing of vault env vars handled in 'writeVaultEnvVars' below

    // list of custom packages to 'pip install' before tests run
    options['customPackages'] = options.get('customPackages', [])

    // extra arguments for plugin tests, i.e. --long-running
    options['extraArgs'] = options.get('extraArgs', '')

    // a Map of extra stages which should run in the same node as iqe tests
    // e.g.
    //  def stage = { sh "echo 'whoami'" }
    //  extraStages = [stageName: stage]
    options['extraStages'] = options.get('extraStages', [:])

    // browser/netlog options
    options['browserlog'] = options.get('browserlog', true)
    options['netlog'] = options.get('netlog', true)

    // force iqe to use specific user
    options['iqeForceDefaultUser'] = options.get('iqeForceDefaultUser', '')

    return options
}

private Map mergeAppOptions(Map options, Map appOptions) {
    /* Merge an app's options with the default options.*/
    if (!(appOptions instanceof Map)) {
        error("Incorrect syntax for appConfigs: 'options' for app is not a Map")
    }

    Map mergedOptions = options + appOptions
    return mergedOptions
}

String runIQE(String plugin, Map appOptions) {
    /*
     * Run IQE sequential tests and parallel tests for a plugin.
     *
     * If an IQE run fails, then fail the stage. Exit code 5 (no tests collected) is treated
     * as a skip for that test bucket; if both buckets have no tests, the stage errors.
     *
     * Returns result of "SUCCESS" or "FAILURE"
     */
    String result
    Integer status
    Boolean noParallelTests = false
    Boolean noSequentialTests = false

    String filterArgs = ''
    String requirementsArgs = ''
    String requirementsPriorityArgs = ''
    String testImportanceArgs = ''
    String browserlog = ''
    String reportportalArgs = ''
    String netlog = ''
    String xdistArgs = ''
    String forceDefaultUser = ''

    if (appOptions['filter']) {
        filterArgs = "-k \"${appOptions['filter']}\""
    }

    if (appOptions['requirements']) {
        requirementsArgs = "--requirements=${appOptions['requirements']}"
    }

    if (appOptions['requirementsPriority']) {
        requirementsPriorityArgs = "--requirements-priority=${appOptions['requirementsPriority']}"
    }

    if (appOptions['testImportance']) {
        testImportanceArgs = "--test-importance=${appOptions['testImportance']}"
    }

    if (appOptions['reportportal']) {
        reportportalArgs = '--reportportal'
    }

    // ibutsu configuration now handled via environment variables in configIQE

    if (appOptions['browserlog']) {
        browserlog = '--browserlog'
    }

    if (appOptions['netlog']) {
        netlog = '--netlog'
    }

    if (appOptions['xdistEnabled']) {
        xdistArgs = "-n ${appOptions['parallelWorkerCount']}"
    }

    if (appOptions['iqeForceDefaultUser']) {
        forceDefaultUser = "--iqe-force-default-user=${appOptions['iqeForceDefaultUser']}"
    }

    String marker = appOptions['marker']
    String extraArgs = appOptions['extraArgs']

    catchError(stageResult: 'FAILURE') {
        String screenshotsDir = sh(
            script: (
                """
                # note: trims trailing newlines and removes spaces
                cat "${env.WORKSPACE}/.env" | grep "SCREENSHOTS_DIR" | cut -f2 -d"=" | tr -d " \n"
                """.stripIndent()
            ),
            returnStdout: true
        )

        if (screenshotsDir) {
            mkdirStatus = sh(
                script: (
                """
                mkdir -p ${screenshotsDir}
                """.stripIndent()
                ),
                returnStatus: true
            )
        }

        // run parallel tests
        String errorMsgParallel = ''
        String errorMsgSequential = ''
        String markerArgs = marker ? "-m \"${marker}\"" : ''

        if (appOptions['xdistEnabled']) {
            markerArgs = marker ? "-m \"parallel and (${marker})\"" : "-m \"parallel\""
            status = sh(
                script: (
                    """
                    set +x && export \$(cat "${env.WORKSPACE}/.env" | xargs) && set -x && \
                    iqe tests plugin ${plugin} -s -v \
                    --junitxml=junit-${plugin}-parallel.xml \
                    ${markerArgs} \
                    ${filterArgs} \
                    ${requirementsArgs} \
                    ${requirementsPriorityArgs} \
                    ${testImportanceArgs} \
                    ${extraArgs} \
                    ${xdistArgs} \
                    --log-file=iqe-${plugin}-parallel.log \
                    ${browserlog} \
                    ${reportportalArgs} \
                    ${netlog} \
                    ${forceDefaultUser} \
                    2>&1
                    """.stripIndent()
                ),
                returnStatus: true
            )
            if (status == 5) {
                noParallelTests = true
            } else if (status > 0) {
                result = 'FAILURE'
                errorMsgParallel = "Parallel test run failed with exit code ${status}."
            }
        } else {
            noParallelTests = true
        }

        // run sequential tests
        if (appOptions['xdistEnabled']) {
            markerArgs = marker ? "-m \"not parallel and (${marker})\"" : "-m \"not parallel\""
        }

        status = sh(
            script: (
                """
                set +x && export \$(cat "${env.WORKSPACE}/.env" | xargs) && set -x && \
                iqe tests plugin ${plugin} -s -v \
                --junitxml=junit-${plugin}-sequential.xml \
                ${markerArgs} \
                ${filterArgs} \
                ${requirementsArgs} \
                ${requirementsPriorityArgs} \
                ${testImportanceArgs} \
                ${extraArgs} \
                --log-file=iqe-${plugin}-sequential.log \
                ${browserlog} \
                ${reportportalArgs} \
                ${netlog} \
                ${forceDefaultUser} \
                2>&1
                """.stripIndent()
            ),
            returnStatus: true
        )
        if (status == 5) {
            noSequentialTests = true
        } else if (status > 0) {
            result = 'FAILURE'
            errorMsgSequential = "Sequential test run failed with exit code ${status}."
        }

        if (noParallelTests && noSequentialTests) {
            error('There were no tests collected in the sequential or parallel test runs.')
        }

        // if there were no failures recorded, it's a success
        result = result ?: 'SUCCESS'

        if (screenshotsDir) {
            dir(screenshotsDir) {
                archiveArtifacts(
                    artifacts: '*.png',
                    allowEmptyArchive: true
                )
            }
        }

        if (errorMsgSequential || errorMsgParallel) {
            error("${errorMsgSequential} ${errorMsgParallel}")
        }
    }

    // archive Ibutsu artifacts
    archiveArtifacts(
        artifacts: '*.tar.gz',
        allowEmptyArchive: true
    )

    catchError {
        archiveArtifacts "iqe-${plugin}-*.log"
        junit "junit-${plugin}-*.xml"
    }

    return result
}

private writeEnvFromCredential(String key, String credentialsId) {
    /* Helper to write a secret value to the .env file */
    withCredentials(
        [string(credentialsId: credentialsId, variable: 'SECRET')]
    ) {
        sh "echo \"${key}=\$SECRET\" >> \"${env.WORKSPACE}/.env\""
    }
}

private writeEnv(String key, String value) {
    /* Helper to write a String env value to the .env file */
    sh "echo \"${key}=${value}\" >> \"${env.WORKSPACE}/.env\""
}

def writeVaultEnvVars(Map options) {
    /* Parse options for vault settings and write the vault env vars to the .env file */

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

    // if using vault, whether or not to verify SSL connections
    options['vaultVerify'] = options.get('vaultVerify', true)

    // if using vault, the vault mount point for the kv engine
    options['vaultMountPoint'] = options.get('vaultMountPoint', pipelineVars.defaultVaultMountPoint)

    if (!options['vaultEnabled']) return

    if (options['vaultUrl']) writeEnv('DYNACONF_IQE_VAULT_URL', options['vaultUrl'])

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
//     }

    writeEnv('DYNACONF_IQE_VAULT_VERIFY', options['vaultVerify'].toString())
    writeEnv('DYNACONF_IQE_VAULT_MOUNT_POINT', options['vaultMountPoint'])
    writeEnv('DYNACONF_IQE_VAULT_LOADER_ENABLED', options['vaultEnabled'].toString())
}

private setupIbutsuEnvVars(Map options) {
    /* Configure ibutsu environment variables based on options */

    // Set defaults if not already set (for backward compatibility)
    options['ibutsu'] = options.get('ibutsu', true)
    options['ibutsuUrl'] = options.get('ibutsuUrl', pipelineVars.defaultIbutsuUrl)
    options['ibutsuBucket'] = options.get('ibutsuAwsBucket', pipelineVars.defaultIbutsuAwsBucket)
    options['ibutsuRegion'] = options.get('ibutsuAwsRegion', pipelineVars.defaultIbutsuAwsRegion)

    // Set up ibutsu environment variables if ibutsu is enabled
    if (options['ibutsu']) {
        writeEnv('IBUTSU_MODE', options['ibutsuUrl'])
        if (options['ibutsuUrl'] == 's3') {
            writeEnv('AWS_BUCKET', options['ibutsuBucket'])
            writeEnv('AWS_REGION', options['ibutsuRegion'])

            def secrets = [
                [path: 'insights/secrets/qe-admin/ibutsu-naberachka',
                    engineVersion: 2,
                    secretValues:
                        [
                            [envVar: 'AWS_ACCESS_KEY_ID',
                            vaultKey: 'AWS_ACCESS_KEY_ID'],
                            [envVar: 'AWS_SECRET_ACCESS_KEY',
                            vaultKey: 'AWS_SECRET_ACCESS_KEY']
                        ]
                ],
            ]

            def configuration = [vaultUrl: 'https://vault.devshift.net/',
                vaultCredentialId: 'vault-approle-cred',
                engineVersion: 1]
            withVault([configuration: configuration, vaultSecrets: secrets]) {
                writeEnv('AWS_ACCESS_KEY_ID', "$AWS_ACCESS_KEY_ID")
                writeEnv('AWS_SECRET_ACCESS_KEY', "$AWS_SECRET_ACCESS_KEY")
            }
        }
        writeEnv('IBUTSU_PROJECT', 'insights-qe')
        writeEnv('IBUTSU_SOURCE', env.BUILD_TAG ?: 'csb-jenkins')
        // Set IBUTSU_TOKEN from Jenkins secret store
        writeEnvFromCredential('IBUTSU_TOKEN', 'ibutsuToken')
    }
}

def configIQE(String appName, Map options) {
    /* Sets up vault and .env files */
    writeEnv('ENV_FOR_DYNACONF', options['envName'])
    writeVaultEnvVars(options)
    setupIbutsuEnvVars(options)

    options['extraEnvVars'].each { key, value ->
        writeEnv(key, value instanceof Closure ? value(env) : value)
    }
}

private createTestStages(String appName, Map appConfig) {
    def appOptions = appConfig['options']

    stage('Configure IQE') {
        configIQE(appName, appOptions)
    }

    def pluginResults = [:]

    appOptions['extraStages'].each { name, closure ->
        stage("${name} stage") {
            closure.call()
        }
    }

    stage("Run ${appName} integration tests") {
        def result = runIQE(appName, appOptions)
        pluginResults[appName] = result
    }

    stage('Results') {
        def pluginsFailed = pluginResults.findAll { it.value == 'FAILURE' }
        def pluginsPassed = pluginResults.findAll { it.value == 'SUCCESS' }

        // stash junit files so that other nodes can read them later
        stash name: "${appName}-stash-files", allowEmpty: true, includes: 'junit-*.xml'

        echo "Plugins passed: ${pluginsPassed.keySet().join(',')}"
        if (pluginsFailed) {
            error "Plugins failed: ${pluginsFailed.keySet().join(',')}"
        }
        else if (!pluginsPassed) {
            error 'No plugins failed nor passed. Were the test runs aborted early?'
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

    appConfigs.each { k, v ->
        // re-define vars, see https://jenkins.io/doc/pipeline/examples/#parallel-multiple-nodes
        def appName = k
        def appConfig = v

        if (!appConfig instanceof Map) {
            error('Incorrect syntax for appConfig: must be a Map')
        }

        def appOptions = mergeAppOptions(options, appConfig.get('options', [:]))
        appConfig['options'] = appOptions
        echo "appOptions: ${appOptions}"

        stages[appName] = {
            if (appOptions['allocateNode']) {
                openShiftUtils.withNodeSelector(appOptions, appOptions['ui']) {
                    createTestStages(appName, appConfig)
                }
            }
            else {
                createTestStages(appName, appConfig)
            }
        }
    }
    return stages
}

/**
 * Write an ibutsu.html file containing a link to the Ibutsu page with test results.
 */
def writeIbutsuHtml() {
    writeFile(
        file: 'ibutsu.html',
        text: (
            '<p>⚠️ You need to be logged in first to access the page</p>' +
            "<a href=\"${pipelineVars.defaultIbutsuFrontendUrl}/project/" +
            "${pipelineVars.defaultIbutsuInsightsProject}" +
            '/results/' +
            "?metadata.jenkins.build_number=%5Beq%5D${env.BUILD_NUMBER}" +
            "&metadata.jenkins.job_name=%5Beq%5D${env.JOB_NAME}\">Click here</a>"
        )
    )
    archiveArtifacts 'ibutsu.html'
}
