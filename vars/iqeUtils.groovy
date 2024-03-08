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
    // we use a ternary here to deal with the empty string
    options['image'] = options.get('image') ? options.get("image") : pipelineVars.iqeCoreImage

    // the namespace that the test pods run in
    options['namespace'] = options.get('namespace')

    // the name of the 'cloud' under the Jenkins kubernetes plugin settings
    options['cloud'] = options.get('cloud', pipelineVars.defaultCloud)

    // the pytest marker expression (-m) used when running tests
    options['marker'] = options.get('marker', pipelineVars.defaultMarker)

    // the pytest filter expression (-k) used when running tests
    options['filter'] = options.get('filter', "")

    // the iqe --requirements expression used when running tests
    options['requirements'] = options.get('requirements', "")

    // the iqe --requirements-priority expression used when running tests
    options['requirementsPriority'] = options.get('requirementsPriority', "")

    // the iqe --test-importance filter expression used when running tests
    options['testImportance'] = options.get('testImportance', "")

    // whether or not to spin up a jenkins pod for running the tests
    options['allocateNode'] = options.get('allocateNode', true)

    // whether or not to report results to reportportal
    options['reportportal'] = options.get('reportportal', false)

    // whether or not to report results to ibutsu
    options['ibutsu'] = options.get('ibutsu', true)

    // the URL of ibutsu
    options['ibutsuUrl'] = options.get('ibutsuUrl', pipelineVars.defaultIbutsuUrl)

    // whether or not to provision a selenium container in the test pod
    options['ui'] = options.get('ui', false)

    // enable pytest-xdist plugin for multiprocess parallelism
    options['xdistEnabled'] = options.get('xdistEnabled', false)

    // number of pytest-xdist workers to use for parallel tests
    options['parallelWorkerCount'] = options.get('parallelWorkerCount', 2)

    // a Map of additional env vars to set in the .env file before running iqe
    options['extraEnvVars'] = options.get('extraEnvVars', [:])

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


private def mergeAppOptions(Map options, Map appOptions) {
    /* Merge an app's options with the default options.*/
    if (!appOptions instanceof Map) {
        error("Incorrect syntax for appConfigs: 'options' for app is not a Map")
    }

    def mergedOptions = [:]
    mergedOptions = options + appOptions
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
    def collectionStatus
    def result
    def status
    def noParallelTests = false
    def noSequentialTests = false

    def filterArgs = ""
    def requirementsArgs = ""
    def requirementsPriorityArgs = ""
    def testImportanceArgs = ""
    def ibutsuArgs = ""
    def browserlog = ""
    def reportportalArgs = ""
    def netlog = ""
    def xdistArgs = ""
    def forceDefaultUser = ""

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

    if (appOptions["reportportal"]) {
        reportportalArgs = "--reportportal"
    }

    if (appOptions['ibutsu']) {
        ibutsuArgs = "-o ibutsu_server=${appOptions['ibutsuUrl']} -o ibutsu_source=${env.BUILD_TAG}"
    }

    if (appOptions["browserlog"]) {
        browserlog = "--browserlog"
    }

    if (appOptions["netlog"]) {
        netlog = "--netlog"
    }

    if (appOptions["xdistEnabled"]) {
        xdistArgs = "-n ${appOptions['parallelWorkerCount']}"
    }

    if (appOptions["iqeForceDefaultUser"]) {
        forceDefaultUser = "--iqe-force-default-user=${appOptions['iqeForceDefaultUser']}"
    }

    def marker = appOptions['marker']
    def extraArgs = appOptions['extraArgs']

    catchError(stageResult: "FAILURE") {
        def screenshotsDir = sh(
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
        def errorMsgParallel = ""
        def errorMsgSequential = ""
        def markerArgs = marker ? "-m \"${marker}\"" : ""

        // check that there are actually tests to run
        if (appOptions["xdistEnabled"]) {
            // if xdist is enabled, try to collect parallel tests
            markerArgs = marker ? "-m \"parallel and (${marker})\"" : "-m \"parallel\""
            collectionStatus = sh(
                script: (
                    """
                    set +x && export \$(cat "${env.WORKSPACE}/.env" | xargs) && set -x && \
                    iqe tests plugin ${plugin} -s -v --collect-only \
                    ${markerArgs} \
                    ${filterArgs} \
                    ${requirementsArgs} \
                    ${requirementsPriorityArgs} \
                    ${testImportanceArgs} \
                    ${extraArgs} \
                    """.stripIndent()
                ),
                returnStatus: true
            )
        } else {
            // if xdist is disabled, just act like we have no parallel tests to run
            collectionStatus = 5
        }

        // status code 5 means no tests collected
        if (collectionStatus == 5) {
            noParallelTests = true
        }
        else if (collectionStatus > 0) {
            result = "FAILURE"
            errorMsgParallel = "Parallel test run collection failed with exit code ${status}"
        }
        // only run tests when the collection status is 0
        else {
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
                    ${ibutsuArgs} \
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
            if (status > 0) {
                result = "FAILURE"
                errorMsgParallel = "Parallel test run failed with exit code ${status}."
            }
        }

        // run sequential tests
        if (appOptions["xdistEnabled"]) {
            // if xdist is enabled, modify marker
            markerArgs = marker ? "-m \"not parallel and (${marker})\"" : "-m \"not parallel\""
        }

        // check that there are actually tests to run
        collectionStatus = sh(
            script: (
                """
                set +x && export \$(cat "${env.WORKSPACE}/.env" | xargs) && set -x && \
                iqe tests plugin ${plugin} -s -v --collect-only \
                ${markerArgs} \
                ${filterArgs} \
                ${requirementsArgs} \
                ${requirementsPriorityArgs} \
                ${testImportanceArgs} \
                ${extraArgs} \
                """.stripIndent()
            ),
            returnStatus: true
        )
        // status code 5 means no tests collected
        if (collectionStatus == 5) {
            noSequentialTests = true
        }
        else if (collectionStatus > 0) {
            result = "FAILURE"
            errorMsgSequential = "Sequential test run collection failed with exit code ${status}"
        }
        // only run tests when the collection status is 0
        else {
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
                    ${ibutsuArgs} \
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
            if (status > 0) {
                result = "FAILURE"
                errorMsgSequential = "Sequential test run failed with exit code ${status}."
            }
        }

        if (noParallelTests && noSequentialTests) {
            error("There were no tests collected in the sequential or parallel test runs.")
        }

        // if there were no failures recorded, it's a success
        result = result ?: "SUCCESS"

        if (screenshotsDir) {
            dir(screenshotsDir) {
                archiveArtifacts(
                    artifacts: "*.png",
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
        artifacts: "*.tar.gz",
        allowEmptyArchive: true
    )

    catchError {
        archiveArtifacts "iqe-${plugin}-*.log"
        junit "junit-${plugin}-*.xml"
    }

    return result
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


def configIQE(String appName, Map options) {
    /* Sets up vault and .env files */
    writeEnv('ENV_FOR_DYNACONF', options['envName'])
    writeVaultEnvVars(options)
    options['extraEnvVars'].each { key, value ->
        writeEnv(key, value instanceof Closure ? value(env) : value)
    }
}


private def createTestStages(String appName, Map appConfig) {
    def appOptions = appConfig['options']

    stage("Configure IQE") {
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

    stage("Results") {
        def pluginsFailed = pluginResults.findAll { it.value == "FAILURE" }
        def pluginsPassed = pluginResults.findAll { it.value == "SUCCESS" }

        // stash junit files so that other nodes can read them later
        stash name: "${appName}-stash-files", allowEmpty: true, includes: "junit-*.xml"

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
        def appName = k
        def appConfig = v

        if (!appConfig instanceof Map) {
            error("Incorrect syntax for appConfig: must be a Map")
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
        file: "ibutsu.html",
        text: (
            "<a href=\"https://ibutsu.insights.corp.redhat.com/results" +
            "?metadata.jenkins.build_number=${env.BUILD_NUMBER}" +
            "&metadata.jenkins.job_name=${env.JOB_NAME}\">Click here</a>"
        )
    )
    archiveArtifacts "ibutsu.html"
}
