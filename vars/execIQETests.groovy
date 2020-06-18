/*
 * This function will do the following:
 * - Create a Jenkins job, with checkbox parameters for each 'app' name, and a dropdown parameter
 *      for the listed envs
 * - Use iqeUtils to run test stages
 * - Return the parallel stage run results to the caller
 *
 * @param appConfigs Map -- see iqeUtils.prepareStages()
 * @param envs String[] of env names
 * @param marker String with default marker expression (optional, if blank "envName" is used)
 * @returns Map with format ["success": String[] successStages, "failed": String[] failedStages]
 */
def call(args = [:]) {
    def appConfigs = args['appConfigs']
    def envs = args['envs']
    def options = args.get('options', [:])
    def defaultMarker = args.get('defaultMarker')
    def defaultFilter = args.get('defaultFilter')

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
            description: "Enter pytest marker expression, leave blank for none"
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

    options['envName'] = params.env
    options['marker'] = params.marker
    options['filter'] = params.filter
    options['ibutsu'] = options.get('ibutsu', true)

    // Run the tests
    lock("${params.env}-test") {
        timeout(time: 150, unit: "MINUTES") {
            results = pipelineUtils.runParallel(iqeUtils.prepareStages(options, appConfigs))
        }
    }

    if (options['ibutsu']) {
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
    }

    return results
}


