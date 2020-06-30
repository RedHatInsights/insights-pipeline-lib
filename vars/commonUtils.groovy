def getBlueBuildUrl() {
    def buildUrl = env.BUILD_URL
    def jobNameSplit = env.JOB_NAME.tokenize('/') as String[]
    def projectName = jobNameSplit[0]
    def blueBuildUrl = buildUrl.replace(
        "job/${projectName}", "blue/organizations/jenkins/${projectName}"
    )
    blueBuildUrl = blueBuildUrl.replace("job/${env.BRANCH_NAME}", "detail/${env.BRANCH_NAME}")

    return blueBuildUrl
}
