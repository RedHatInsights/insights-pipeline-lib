def call(params = [:]) {
    def serviceSet = params['serviceSet']
    def env = params.get('env')
    def project = params['project']
    def secretsSrcProject = params.get('secretsSrcProject', "secrets")
    def templateDir = params.get('templateDir', "templates")
    def skip = params.get('skip')
    def pipInstall = params.get('pipInstall', true)
    def watch = params.get('watch', true)

    if (pipInstall) {
        checkOutRepo(targetDir: pipelineVars.e2eDeployDir, repoUrl: pipelineVars.e2eDeployRepoSsh, credentialsId: pipelineVars.gitSshCreds)
        sh "python3.6 -m venv ${pipelineVars.venvDir}"
        sh "${pipelineVars.venvDir}/bin/pip install --upgrade pip"
        dir(pipelineVars.e2eDeployDir) {
            sh "${pipelineVars.venvDir}/bin/pip install -r requirements.txt"
        }
    }
    dir(pipelineVars.e2eDeployDir) {
        def watchArg = watch ? " -w " : " "
        def envArg = env ? " -e env/${env}.yml " : " "
        def cmd = "${pipelineVars.venvDir}/bin/ocdeployer deploy${watchArg}-f -t ${templateDir} -s ${serviceSet}${envArg}${project} --secrets-src-project ${secretsSrcProject}"
        if (skip) cmd = "${cmd} --skip ${skip.join(",")}"
        sh cmd
    }
}
