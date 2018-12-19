import com.cloudbees.groovy.cps.NonCPS


@NonCPS
def triggeredByComment(currentBuild) {
    def issueCommentCause = currentBuild.rawBuild.getCause(org.jenkinsci.plugins.pipeline.github.trigger.IssueCommentCause) != null
    def rebuildCause = currentBuild.rawBuild.getCause(com.sonyericsson.rebuild.RebuildCause) != null
    def replayCause = currentBuild.rawBuild.getCause(org.jenkinsci.plugins.workflow.cps.replay.ReplayCause) != null
    def isStartedByUser = currentBuild.rawBuild.getCause(hudson.model.Cause$UserIdCause) != null
    if (issueCommentCause || rebuildCause || replayCause || isStartedByUser) {
        return true
    } else {
        echo('Build not started by issue comment trigger, rebuild/replay trigger, or manual build trigger')
        return false
    }
}


def call() {
    return triggeredByComment(currentBuild)
}
