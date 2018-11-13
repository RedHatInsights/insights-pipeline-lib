import com.cloudbees.groovy.cps.NonCPS


@NonCPS
def triggeredByComment(currentBuild) {
    def cause = currentBuild.rawBuild.getCause(org.jenkinsci.plugins.pipeline.github.trigger.IssueCommentCause)
    if (cause) {
        return true
    } else {
        echo('Build not started by issue comment trigger')
        return false
    }
}


def call() {
    return triggeredByComment(currentBuild)
}
