// Utils and helpers
package com.redhat.insights_pipeline;

import com.cloudbees.groovy.cps.NonCPS;


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
