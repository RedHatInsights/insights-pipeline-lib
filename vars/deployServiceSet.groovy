def call(params = [:]) {
    serviceSet = params['serviceSet']
    env = params['env']
    project = params['project']
    secretsSrcProject = params.get('secretsSrcProject', "secrets")
    skip = params.get('skip')

    checkOutRepo(targetDir: pipelineVars.e2eDeployDir, repoUrl: pipelineVars.e2eDeployRepoSsh, credentialsId: pipelineVars.gitSshCreds)
    sh "python3.6 -m venv ${pipelineVars.venvDir}"
    sh "${pipelineVars.venvDir}/bin/pip install --upgrade pip"
    dir(pipelineVars.e2eDeployDir) {
        sh "${pipelineVars.venvDir}/bin/pip install -r requirements.txt"
        cmd = "${pipelineVars.venvDir}/bin/ocdeployer deploy -f -s ${serviceSet} -e ${env}.yml ${project} --secrets-src-project ${secretsSrcProject}"
        if (skip) cmd = "${cmd} --skip ${skip}"
        sh cmd
    }
}