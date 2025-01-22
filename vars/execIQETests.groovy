/*
 * Function will do the following:
 * - Create a Jenkins job, with checkbox parameters for each 'app' name, text boxes for the pytest
 *     marker and filter expression, and a dropdown parameter to select a different env
 * - Use iqeUtils to run test stages
 * - Return the parallel stage run results to the caller
 *
 * @param appConfigs Map -- see iqeUtils
 * @param options Map -- see iqeUtils
 * @param lockName String the name of the resource to lock (using lockable resources plugin) when
 *      job runs. By default it will be "${envName}-test"
 *
 * @return Map with format ["success": String[] successStages, "failed": String[] failedStages]
 */

def call(args = [:]) {
    def appConfigs = args['appConfigs']
    def options = args.get('options', [:])
    def lockName = args.get('lockName')

    options['envName'] = options.get('envName', '')
    options['marker'] = options.get('marker', '')
    options['namespace'] = options.get('namespace', 'dno--jenkins-csb-insights-qe')
    options['filter'] = options.get('filter', '')
    options['requirements'] = options.get('requirements', '')
    options['requirementsPriority'] = options.get('requirementsPriority', '')
    options['testImportance'] = options.get('testImportance', '')
    options['image'] = options.get('image', '')
    options['ibutsu'] = options.get('ibutsu', true)
    options['reportportal'] = options.get('reportportal', false)
    options['cloud'] = options.get('cloud', pipelineVars.upshiftCloud)
    options['timeout'] = options.get('timeout', 150)
    options['jenkinsSlaveImage'] = options.get('jenkinsSlaveImage', pipelineVars.centralCIjenkinsSlaveImage)
    options['limitMemory'] = options.get('resourceLimitMemory', "1Gi")

    // Run the tests
    if (!lockName) lockName = "${options['envName']}-test"
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
