// Check that code coverage isn't below a certain threshold
// Assumes that python code coverage has already been run


def call(parameters = [:]) {

    withStatusContext.coverage {
        def threshold = parameters.get('threshold', 80)

        def status = 99

        status = sh(
            script: "${pipelineVars.userPath}/pipenv run coverage html --fail-under=${threshold} --skip-covered",
            returnStatus: true
        )

        archiveArtifacts 'htmlcov/*'

        if (status != 0) { 
            throw new Exception("Code coverage is below threshold of ${threshold}%")
        }
    }
}
