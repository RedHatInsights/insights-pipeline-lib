class pipelineVars implements Serializable {
    String jenkinsSvcAccount = "jenkins"
    String defaultNameSpace = "jenkins"

    String gitSshCreds = "insightsdroid-ssh-git"
    String gitHttpCreds = "InsightsDroidGitHubHTTP"

    String userPath = "~/.local/bin"
    String venvDir = "~/.venv"

    String smokeTestResourceLabel = "smoke_test_projects"
    String e2eDeployDir = 'e2e-deploy'
    String e2eDeployRepo = 'https://github.com/RedHatInsights/e2e-deploy.git'
    String jenkinsConfigRepo = 'https://github.com/RedHatInsights/iqe-jenkins.git'
    String e2eDeployRepoSsh = 'git@github.com:RedHatInsights/e2e-deploy.git'
    String e2eTestsDir = 'e2e-tests'
    String e2eTestsRepo = 'https://github.com/RedHatInsights/e2e-tests.git'
    String e2eTestsRepoSsh = 'git@github.com:RedHatInsights/e2e-tests.git'

    String prodCluster = "api.insights.openshift.com"
    String devCluster = "api.insights-dev.openshift.com"
    String defaultVaultUrl = "https://vault.devshift.net"
    String defaultVaultAppRoleIdCredential = "vaultAppRoleId"
    String defaultVaultAppRoleTokenCredential = "vaultAppRoleToken"
    String defaultVaultMountPoint = "insights"
    String defaultIbutsuUrl = "https://ibutsu-api.cloud.paas.psi.redhat.com"

    String jenkinsSlaveImage = (
        'registry.access.redhat.com/openshift3/jenkins-slave-base-rhel7:v3.11'
    )
    String centralCIjenkinsSlaveImage = (
        'docker-registry.upshift.redhat.com/insights-qe/jenkins-slave-base:latest'
    )
    String iqeCoreImage = 'quay.io/cloudservices/iqe-core:latest'
    String iqeTestsImage = 'quay.io/cloudservices/iqe-tests:latest'
    String seleniumImage = 'quay.io/redhatqe/selenium-standalone:latest'

    String defaultCloud = 'openshift'
    String defaultNamespace = 'jenkins'
    String upshiftCloud = 'upshift'
    String upshiftNameSpace = 'insights-qe-ci'

    String slackDefaultUrl = 'https://ansible.slack.com/services/hooks/jenkins-ci/'
    String slackDefaultChannel = '#insights-qe-feed'
    String slackDeployAlertsChannel = "#deploy-alerts"

    String quayBaseUri = 'quay.io/cloudservices'
    String quayUser = "cloudservices+push"
    String quayPushCredentialsId = "quay-cloudservices-push-token"
}
