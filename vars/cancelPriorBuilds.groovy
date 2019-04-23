@NonCPS
def cancelPreviousBuilds() {
    // https://stackoverflow.com/a/48956028/6476672
    def jobName = env.JOB_NAME
    def buildNumber = env.BUILD_NUMBER.toInteger()
    /* Get job name */
    def currentJob = Jenkins.instance.getItemByFullName(jobName)

    /* Iterating over the builds for specific job */
    for (def build : currentJob.builds) {
        /* If there is a build that is currently running and it's not current build */
        if (build.isBuilding() && build.number.toInteger() != buildsNumber) {
            /* Then stop it */
            build.doStop()
        }
    }
}


def call() {
    cancelPreviousBuilds()
}
