// Run the code block only after checking SCM and verifying this is a master branch or an untested PR

def call(Closure body) {
    scmVars = checkout scm

    echo "env.CHANGE_ID:                  ${env.CHANGE_ID}"
    echo "env.BRANCH_NAME:                ${env.BRANCH_NAME}"
    echo "GIT_COMMIT:                     ${scmVars.GIT_COMMIT}"
    echo "GIT_PREVIOUS_SUCCESSFUL_COMMIT: ${scmVars.GIT_PREVIOUS_SUCCESSFUL_COMMIT}"
    echo "GIT_URL:                        ${scmVars.GIT_URL}"

    if (env.CHANGE_ID || (env.BRANCH_NAME == 'master' && scmVars.GIT_COMMIT != scmVars.GIT_PREVIOUS_SUCCESSFUL_COMMIT)) {
        body()
    } else {
        echo 'runIfMasterOrPullReq -- not a PR or not a new commit on master.'
    }
}
