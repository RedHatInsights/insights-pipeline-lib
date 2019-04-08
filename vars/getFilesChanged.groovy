def call(parameters = [:]) {
    // Check out a repo and then return a list of which files/folders changed between oldCommit and newCommit
    repoDir = parameters.get['repoDir']
    oldCommit = parameters['oldCommit']
    newCommit = parameters.get['newCommit']

    dir(repoDir) {
        sh "git config --add remote.origin.fetch +refs/heads/*:refs/remotes/origin/*"
        sh "git fetch"
        sh "git diff --name-only ${oldCommit} ${newCommit} | cut -s -f1,2 -d'/' > .files_changed.txt"
        def data = readFile("files_changed.txt").split('\n')
        sh "rm .files_changed.txt"
        return data
    }
}