// Notifies a github context with a certain status. Replaces URLs with blue ocean URLs
import com.redhat.insights_pipeline.Const


def call(String context, String status) {
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
            case Const.lintContext:
                targetUrl =  "${blueBuildUrl}tests"
                break
            case Const.unitTestContext:
                targetUrl =  "${blueBuildUrl}tests"
                break
            case Const.coverageContext:
                targetUrl = "${env.BUILD_URL}artifact/htmlcov/index.html"
                break
            default:
                targetUrl = env.RUN_DISPLAY_URL
                break
        }
    }

    try {
        githubNotify context: context, status: status, targetUrl: targetUrl
    } catch (err) {
        msg = err.getMessage()
        echo "Error notifying GitHub: ${msg}"
    }
}
