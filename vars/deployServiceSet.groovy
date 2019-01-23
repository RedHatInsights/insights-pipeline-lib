def call(params = [:]) {
    serviceSet = params['serviceSet']
    env = params.get('env')
    project = params['project']
    secretsSrcProject = params.get('secretsSrcProject', "secrets")
    templateDir = params.get('templateDir', "templates")
    skip = params.get('skip')

    checkOutRepo(targetDir: pipelineVars.e2eDeployDir, repoUrl: pipelineVars.e2eDeployRepoSsh, credentialsId: pipelineVars.gitSshCreds)
    sh "python3.6 -m venv ${pipelineVars.venvDir}"
    sh "${pipelineVars.venvDir}/bin/pip install --upgrade pip"
    dir(pipelineVars.e2eDeployDir) {
        sh "${pipelineVars.venvDir}/bin/pip install -r requirements.txt"
        def envArg = " "
        if (env) envArg = " -e env/${env}.yml"
        cmd = "${pipelineVars.venvDir}/bin/ocdeployer deploy -f -t ${templateDir} -s ${serviceSet}${envArg}${project} --secrets-src-project ${secretsSrcProject}"
        if (skip) cmd = "${cmd} --skip ${skip.join(",")}"
        sh cmd
    }
}
