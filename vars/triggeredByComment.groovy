import com.cloudbees.groovy.cps.NonCPS


@NonCPS
def triggeredByComment(currentBuild) {
    def issueCommentCause = currentBuild.rawBuild.getCause(org.jenkinsci.plugins.pipeline.github.trigger.IssueCommentCause) != null
    def rebuildCause = currentBuild.rawBuild.getCause(com.sonyericsson.rebuild.RebuildCause) != null
    def replayCause = currentBuild.rawBuild.getCause(org.jenkinsci.plugins.workflow.cps.replay.ReplayCause) != null
    if (issueCommentCause || rebuildCause || replayCause) {
        return true
    } else {
        echo('Build not started by issue comment trigger or rebuild/replay trigger')
        return false
    }
}


def call() {
    return triggeredByComment(currentBuild)
}
