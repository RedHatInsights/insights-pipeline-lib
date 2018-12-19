import com.cloudbees.groovy.cps.NonCPS


@NonCPS
def triggeredByComment(currentBuild) {
    def issueCommentCause = currentBuild.rawBuild.getCause(org.jenkinsci.plugins.pipeline.github.trigger.IssueCommentCause)
    def rebuildCause = currentBuild.rawBuild.getCause(com.sonyericsson.rebuild.RebuildCause)
    if (issueCommentCause || rebuildCause) {
        return true
    } else {
        echo('Build not started by issue comment trigger or rebuild trigger')
        return false
    }
}


def call() {
    return triggeredByComment(currentBuild)
}
