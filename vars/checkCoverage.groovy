// Check that code coverage isn't below a certain threshold
// Assumes that python code coverage has already been run
import com.redhat.constants.Const;


def call(threshold = 80) {
    def status = 99

    status = sh(
        script: "${Const.userPath}/pipenv run coverage html --fail-under=${threshold} --skip-covered",
        returnStatus: true
    )

    archiveArtifacts 'htmlcov/*'

    withStatusContext.coverage {
        assert status == 0
    }
}
