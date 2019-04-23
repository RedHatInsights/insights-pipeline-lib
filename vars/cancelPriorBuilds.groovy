import hudson.model.Run
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper


@NonCPS
void cancelPreviousRunningBuilds(int maxBuildsToSearch = 20) {
    RunWrapper b = currentBuild
    for (int i=0; i<maxBuildsToSearch; i++) {
        b = b.getPreviousBuild();
        if (b == null) break;
        Run<?,?> rawBuild = b.rawBuild
        if (rawBuild.isBuilding()) {
            rawBuild.doStop()
        }
    }
}


def call() {
    cancelPreviousRunningBuilds()
}
