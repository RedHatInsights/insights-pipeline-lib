// Utils and helpers

class Utils {
    @NonCPS
    static def triggeredByComment(currentBuild) {
        def cause = currentBuild.rawBuild.getCause(org.jenkinsci.plugins.pipeline.github.trigger.IssueCommentCause)
        if (cause) {
            return true
        } else {
            echo('Build not started by issue comment trigger')
            return false
        }
    }
}
