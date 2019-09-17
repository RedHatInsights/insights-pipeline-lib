import groovy.transform.Field

@Field
def priorBuildsCancelled = false

def call() {

    if (! priorBuildsCancelled) {

        def buildNumber = env.BUILD_NUMBER as int

        if (buildNumber > 1) {
            milestone(buildNumber - 1)
        }

        milestone(buildNumber)

        priorBuildsCancelled = true
    }
}
