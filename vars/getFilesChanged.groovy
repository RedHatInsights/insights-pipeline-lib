def call(parameters = [:]) {
    // Check out a repo and then return a list of which files/folders changed between oldCommit and newCommit
    repoDir = parameters['repoDir']
    oldCommit = parameters['oldCommit']
    newCommit = parameters['newCommit']

    dir(repoDir) {
        sh "git config --add remote.origin.fetch +refs/heads/*:refs/remotes/origin/*"
        sh "git fetch"
        data = sh(
            script: "git diff --name-only ${oldCommit} ${newCommit} | cut -s -f1,2 -d'/'",
            returnStdout: true
        ).trim()
        return data
    }
}