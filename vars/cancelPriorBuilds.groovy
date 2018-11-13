

def call() {
    milestone()

    // trick to cancel previous builds, see https://issues.jenkins-ci.org/browse/JENKINS-40936
    // avoids quick PR updates triggering too many concurrent tests
    for (int i = 0; i < (env.BUILD_NUMBER as int); i++) {
        milestone()
    }
}
