def call(params = [:]) {
    service = params['service']
    env = params['env']
    project = params['project']
    secretsSrcProject = params.get('secretsSrcProject', "secrets")

    if (!fileExists(pipelineVars.e2eDeployDir)) {
        checkOutRepo(targetDir: pipelineVars.e2eDeployDir, repoUrl: pipelineVars.e2eDeployRepoSsh, credentialsId: pipelineVars.gitSshCreds)
    }
    sh "python3.6 -m venv ${pipelineVars.venvDir}"
    sh "${pipelineVars.venvDir}/bin/pip install --upgrade pip"
    dir(pipelineVars.e2eDeployDir) {
        sh "${pipelineVars.venvDir}/bin/pip install -r requirements.txt"
        sh "${pipelineVars.venvDir}/bin/ocdeployer deploy -f --pick ${service} -e env/${env}.yml ${project} --secrets-src-project ${secretsSrcProject}"
    }
}
