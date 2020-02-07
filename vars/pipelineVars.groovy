class pipelineVars implements Serializable {
    String jenkinsSvcAccount = "jenkins"
    String defaultNameSpace = "jenkins"

    String gitSshCreds = "insightsdroid-ssh-git"

    String userPath = "~/.local/bin"
    String venvDir = "~/.venv"

    String smokeTestResourceLabel = "smoke_test_projects"
    String e2eDeployDir = 'e2e-deploy'
    String e2eDeployRepo = 'https://github.com/RedHatInsights/e2e-deploy.git'
    String e2eDeployRepoSsh = 'git@github.com:RedHatInsights/e2e-deploy.git'
    String e2eTestsDir = 'e2e-tests'
    String e2eTestsRepo = 'https://github.com/RedHatInsights/e2e-tests.git'
    String e2eTestsRepoSsh = 'git@github.com:RedHatInsights/e2e-tests.git'

    String prodCluster = "api.insights.openshift.com"
    String devCluster = "api.insights-dev.openshift.com"

    String jenkinsSlaveImage = (
        'registry.access.redhat.com/openshift3/jenkins-slave-base-rhel7:v3.11'
    )
    String centralCIjenkinsSlaveImage = (
        'docker-registry.upshift.redhat.com/ccit/jenkins-slave-centos7:2.150.2'
    )
    String iqeCoreImage = 'quay.io/cloudservices/iqe-core'
    String iqeTestsImage = 'quay.io/cloudservices/iqe-tests'
    String seleniumImage = 'quay.io/redhatqe/selenium-standalone'

    String defaultCloud = 'openshift'
    String defaultNamespace = 'jenkins'
    String upshiftCloud = 'upshift'
    String upshiftNameSpace = 'insights-qe-ci'

    String slackDefaultUrl = 'https://ansible.slack.com/services/hooks/jenkins-ci/'
    String slackDefaultChannel = '#insights-qe-feed'
}
