def call() {
    milestone()
    def buildNumber = env.BUILD_NUMBER as int
    milestone(buildNumber - 1)
    milestone(buildNumber)
}
