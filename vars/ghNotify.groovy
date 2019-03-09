// Notifies a github context with a certain status. Replaces URLs with blue ocean URLs


private def URLShortener(String url) {
    // we would prefer to not expose internal hostnames
    def get = new URL("https://url.corp.redhat.com/new?${url}").openConnection()
    if (get.getResponseCode() == 200) {
        return get.getInputStream().getText()
    }
}


def call(parameters = [:]) {
    def context = parameters['context']
    def status = parameters['status']
    def shortenURL = parameters.get('shortenURL', false)

    def targetUrl

    def buildUrl = env.BUILD_URL
    def jobNameSplit = JOB_NAME.tokenize('/') as String[]
    def projectName = jobNameSplit[0]
    def blueBuildUrl = buildUrl.replace("job/${projectName}", "blue/organizations/jenkins/${projectName}")
    blueBuildUrl = blueBuildUrl.replace("job/${env.BRANCH_NAME}", "detail/${env.BRANCH_NAME}")

    if (status == "PENDING") {
        // Always link to the fancy blue ocean UI while the job is running ...
        targetUrl = env.RUN_DISPLAY_URL
    } else {
        switch (context) {
            case pipelineVars.lintContext:
                targetUrl =  "${blueBuildUrl}tests"
                break
            case pipelineVars.unitTestContext:
                targetUrl =  "${blueBuildUrl}tests"
                break
            case pipelineVars.coverageContext:
                targetUrl = "${env.BUILD_URL}artifact/htmlcov/index.html"
                break
            case pipelineVars.artifactsContext:
                targetUrl = "${blueBuildUrl}artifacts"
                break
            default:
                targetUrl = env.RUN_DISPLAY_URL
                break
        }
    }

    try {
        targetUrl = shortenURL ? URLShortener(targetUrl) : targetUrl
        githubNotify context: context, status: status, targetUrl: targetUrl
    } catch (err) {
        msg = err.getMessage()
        echo "Error notifying GitHub: ${msg}"
    }
}
