// Returns a list of file paths that are changed in a PR

def call() {
    def changedFiles = []
    currentBuild.changeSets.each { changeSet ->
        changeSet.items.each { item ->
            item.affectedFiles.each { file ->
                changedFiles.add(file.path)
            }
        }
    }
    changedFiles
}
