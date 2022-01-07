/*
 * This function will do the following:
 * - Create a Jenkins job, with checkbox parameters for each 'app' name, text boxes for the pytest
 *     marker and filter expression, and a dropdown parameter to select a different env
 * - Use iqeUtils to run test stages
 * - Return the parallel stage run results to the caller
 *
 * @param appConfigs Map -- see iqeUtils
 * @param envs String[] -- list of environments this test job can run against
 * @param options Map -- see iqeUtils
 * @param defaultMarker String with default marker expression (optional, default is pipelineVars.defaultMarker)
 * @param defaultFilter String for default pytest filter expression (optional)
 * @param requirements String for iqe --requirements expression (optional)
 * @param requirementsPriority String for iqe --requirementsPriority expression (optional)
 * @param testImportance String for iqe --test-importance expression (optional)
 * @param extraJobProperties Array of job properties to append to the properties that this function
 *      creates (optional)
 * @param lockName String the name of the resource to lock (using lockable resources plugin) when
 *      job runs. By default it will be "${envName}-test"
 *
 * @returns Map with format ["success": String[] successStages, "failed": String[] failedStages]
 * @throws AbortException if the 'RELOAD' parameter is true
 */
def call(args = [:]) {
    def appConfigs = args['appConfigs']
    def envs = args['envs']
    def options = args.get('options', [:])
    def defaultMarker = args.get('defaultMarker', pipelineVars.defaultMarker)
    def defaultFilter = args.get('defaultFilter')
    def requirements = args.get('requirements')
    def requirementsPriority = args.get('requirementsPriority')
    def testImportance = args.get('testImportance')
    def defaultImage = args.get('defaultImage')
    def extraJobProperties = args.get('extraJobProperties', [])
    def lockName = args.get('lockName')

    p = []
    // Add a param option for simply reloading this job
    p.add(
        [
            $class: 'BooleanParameterDefinition',
            name: "RELOAD", defaultValue: false, description: "Reload the job's config and quit"
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
            name: "marker", defaultValue: defaultMarker != null ? defaultMarker : pipelineVars.defaultMarker,
            description: "Enter pytest marker expression"
        ]
    )

    // Add text field for test filter
    p.add(
        [
            $class: 'StringParameterDefinition',
            name: "filter", defaultValue: defaultFilter ? defaultFilter : "",
            description: "Enter pytest filter expression, leave blank for none"
        ]
    )

    // Add text field for test filter
    p.add(
        [
            $class: 'StringParameterDefinition',
            name: "requirements", defaultValue: requirements ? requirements : "",
            description: "Enter iqe --requirements expression, leave blank for none"
        ]
    )

    // Add text field for test filter
    p.add(
        [
            $class: 'StringParameterDefinition',
            name: "requirementsPriority", defaultValue: requirementsPriority ? requirementsPriority : "",
            description: "Enter iqe --requirements-priority expression, leave blank for none"
        ]
    )

    // Add text field for test filter
    p.add(
        [
            $class: 'StringParameterDefinition',
            name: "testImportance", defaultValue: testImportance ? testImportance : "",
            description: "Enter iqe --test-importance expression, leave blank for none"
        ]
    )

    // Add text field for test image
    p.add(
        [
            $class: 'StringParameterDefinition',
            name: "image", defaultValue: defaultImage ? defaultImage : "",
            description: "Enter the name of the iqe plugin image, leave blank for iqe-core"
        ]
    )

    def jobProperties = [parameters(p)]
    jobProperties.addAll(extraJobProperties)

    properties(jobProperties)

    pipelineUtils.checkForReload()

    // For build #1, only load the pipeline and exit
    // This is so the next time the job is built, "Build with parameters" will be available
    if (env.BUILD_NUMBER.toString() == "1") {
        echo "Initial run, loaded pipeline job and now exiting."
        currentBuild.description = "loaded params"
        return
    }

    // if an app has been unchecked do not run tests for it
    appConfigs = appConfigs.findAll { params[it.key] == true }

    options['envName'] = params.env
    options['marker'] = params.marker
    options['filter'] = params.filter
    options['requirements'] = params.requirements
    options['requirementsPriority'] = params.requirementsPriority
    options['testImportance'] = params.testImportance
    options['image'] = params.image
    options['ibutsu'] = options.get('ibutsu', true)
    options['reportportal'] = options.get('reportportal', false)
    options['cloud'] = options.get('cloud', pipelineVars.upshiftCloud)
    options['timeout'] = options.get('timeout', 150)

    // Run the tests
    if (!lockName) lockName = "${params.env}-test"
    lock(lockName) {
        timeout(time: options['timeout'], unit: "MINUTES") {
            results = pipelineUtils.runParallel(iqeUtils.prepareStages(options, appConfigs))
        }
    }

    if (options['ibutsu']) {
        node {
            iqeUtils.writeIbutsuHtml()
        }
    }

    if (!results) error("Found no test results")
    def totalResults = results['success'].size() + results['failed'].size()
    if (totalResults != appConfigs.keySet().size()) error("Did not find test results for expected number of apps")

    return results
}
