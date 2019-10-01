
def call() {

    if (! env.priorBuildsCancelled) {

        def buildNumber = env.BUILD_NUMBER as int

        if (buildNumber > 1) {
            milestone(buildNumber - 1)
        }

        milestone(buildNumber)

        env.priorBuildsCancelled = true
    }
}
