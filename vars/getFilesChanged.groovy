def call(parameters = [:]) {
    // Check out a repo and then return a list of which files/folders changed between oldCommit and newCommit
    repoDir = parameters['repoDir']
    oldCommit = parameters['oldCommit']
    newCommit = parameters['newCommit']

    dir(repoDir) {
        withEnv([
            "GIT_COMMITTER_NAME=nobody",
            "GIT_COMMITTER_EMAIL=nobody@redhat.com"
        ]) {
            sh "git config --add remote.origin.fetch +refs/heads/*:refs/remotes/origin/*"
            sh "git fetch"
            dataNoCut = sh(
                script: "git diff --name-only ${oldCommit} ${newCommit}",
                returnStdout: true
            ).trim().split('\n')
            echo "git commit files changed: ${dataNoCut}"

            // Cut to narrow things down to only template dir name and service set
            data = sh(
                script: "git diff --name-only ${oldCommit} ${newCommit} | cut -s -f1,2 -d'/'",
                returnStdout: true
            ).trim().split('\n')
        }
        dataSet = data as Set  // remove dupes
        echo "filesChanged: ${dataSet}"
        return dataSet
    }
}
