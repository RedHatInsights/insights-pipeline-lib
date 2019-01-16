// Helper to check out a github repo


def call(parameters = [:]){ 
    targetDir = parameters['targetDir']
    repoUrl = parameters['repoUrl']
    branch = parameters.get('branch', 'master')

    checkout([
        $class: 'GitSCM',
        branches: [[name: branch]],
        doGenerateSubmoduleConfigurations: false,
        extensions: [
            [$class: 'RelativeTargetDirectory', relativeTargetDir: targetDir],
        ],
        submoduleCfg: [],
        userRemoteConfigs: [
            [credentialsId: 'github', url: repoUrl]
        ]
    ])
}
