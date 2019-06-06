def call(params = [:]) {
    def service = params['service']
    def env = params['env']
    def project = params['project']
    def secretsSrcProject = params.get('secretsSrcProject', "secrets")
    def pipInstall = params.get('pipInstall', true)

    if (pipInstall) {
        checkOutRepo(targetDir: pipelineVars.e2eDeployDir, repoUrl: pipelineVars.e2eDeployRepoSsh, credentialsId: pipelineVars.gitSshCreds)
        sh "python3.6 -m venv ${pipelineVars.venvDir}"
        sh "${pipelineVars.venvDir}/bin/pip install --upgrade pip"
        dir(pipelineVars.e2eDeployDir) {
            sh "${pipelineVars.venvDir}/bin/pip install -r requirements.txt"
        }
    }
    dir(pipelineVars.e2eDeployDir) {
        sh "${pipelineVars.venvDir}/bin/ocdeployer deploy -f --pick ${service} -e env/${env}.yml ${project} --secrets-src-project ${secretsSrcProject}"
    }
}
