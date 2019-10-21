/**
 * Various helpers for interacting with git / github
 */


private def getFilesFromChangeSets() {
    def changedFiles = []
    currentBuild.changeSets.each { changeSet ->
        changeSet.items.each { item ->
            item.affectedFiles.each { file ->
                changedFiles.add(file.path)
            }
        }
    }
    dataSet = changedFiles as Set
    echo "filesChanged: ${dataSet}"
    return dataSet
}


private def getFilesFromCommits(oldCommit, newCommit, repoDir) {
    // Return a list of which files/folders changed between
    // oldCommit and newCommit in the given repoDir
    dir(repoDir) {
        withEnv([
            "GIT_COMMITTER_NAME=nobody",
            "GIT_COMMITTER_EMAIL=nobody@redhat.com"
        ]) {
            sh "git config --add remote.origin.fetch +refs/heads/*:refs/remotes/origin/*"
            sh "git fetch"
            data = sh(
                script: "git diff --name-only ${oldCommit} ${newCommit}",
                returnStdout: true
            ).trim().split('\n')
        }
        echo "git commit files changed: ${data}"
        dataSet = data as Set  // remove dupes
        echo "filesChanged: ${dataSet}"
        return dataSet
    }
}


def getFilesChanged(parameters = [:]) {
    def oldCommit = parameters.get('oldCommit')
    def newCommit = parameters.get('newCommit')
    def repoDir = parameters.get('repoDir', env.WORKSPACE)

    if (oldCommit && newCommit && repoDir) {
        return getFilesFromCommits(oldCommit, newCommit, repoDir)
    } else {
        return getFilesFromChangeSets()
    }
}


private def URLShortener(String url) {
    // we would prefer to not expose internal hostnames
    def response = httpRequest "https://url.corp.redhat.com/new?${url}"
    if (response.status == 200) {
        return response.content
    }
}


def ghNotify(parameters = [:]) {
    // Notifies a github context with a certain status. Replaces URLs with blue ocean URLs
    def context = parameters['context']
    def status = parameters['status']
    def shortenURL = parameters.get('shortenURL', false)

    def targetUrl

    def buildUrl = env.BUILD_URL
    def jobNameSplit = JOB_NAME.tokenize('/') as String[]
    def projectName = jobNameSplit[0]
    def blueBuildUrl = buildUrl.replace(
        "job/${projectName}", "blue/organizations/jenkins/${projectName}"
    )
    blueBuildUrl = blueBuildUrl.replace("job/${env.BRANCH_NAME}", "detail/${env.BRANCH_NAME}")

    if (status == "PENDING") {
        // Always link to the fancy blue ocean UI while the job is running ...
        targetUrl = env.RUN_DISPLAY_URL
    } else {
        switch (context) {
            case "lint":
                targetUrl =  "${blueBuildUrl}tests"
                break
            case "unittest":
                targetUrl =  "${blueBuildUrl}tests"
                break
            case "coverage":
                targetUrl = "${env.BUILD_URL}artifact/htmlcov/index.html"
                break
            case "artifacts":
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


def checkOutRepo(parameters = [:]) {
    def targetDir = parameters['targetDir']
    def repoUrl = parameters['repoUrl']
    def branch = parameters.get('branch', 'master')
    def credentialsId = parameters.get('credentialsId', 'github')

    return checkout([
        $class: 'GitSCM',
        branches: [[name: branch]],
        doGenerateSubmoduleConfigurations: false,
        extensions: [
            [$class: 'RelativeTargetDirectory', relativeTargetDir: targetDir],
        ],
        submoduleCfg: [],
        userRemoteConfigs: [
            [credentialsId: credentialsId, url: repoUrl]
        ]
    ])
}


def getStatusContext(String context) {
    return "continuous-integration/jenkins/${context.toLowerCase().replaceAll('\\s','')}"
}


def withStatusContext(String context, Boolean shortenURL = false, Closure body) {
    /**
     * Context manager which notifies github if the operation succeeds or fails
     * Example: gitUtils.withStatusContext("unit-tests") { }
     */
    context = getStatusContext(context)
    ghNotify context: context, shortenURL: shortenURL, status: "PENDING"

    try {
        body()
        ghNotify context: context, shortenURL: shortenURL, status: "SUCCESS"
    } catch (err) {
        echo err.toString()
        echo err.getMessage()
        try {
            def trace = err.getStackTrace() as String[]
            echo trace.join('\n')
        } catch (innerErr) {
            echo innerErr.toString()
        } finally {
            currentBuild.result = "FAILURE"
            ghNotify context: context, shortenURL: shortenURL, status: "FAILURE"
        }
    }
}


def stageWithContext(String name, Boolean shortenURL = true, Closure body) {
    /**
    * This is a small improvement to reduce nested calls.
    *
    * Before:
    * stage("Run-integration-tests") {
    *     gitUtils.withStatusContext(env.STAGE_NAME, true) {
    *         body()
    *     }
    * }
    *
    * After:
    * gitUtils.stageWithContext("Run-integration-tests") {
    *     body()   
    * }
    */
    stage(name) {
        gitUtils.withStatusContext(name, shortenURL) {
            body()
        }
    }
}
