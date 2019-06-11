class pipelineVars implements Serializable {
    String lintContext = "continuous-integration/jenkins/lint"
    String unitTestContext = "continuous-integration/jenkins/unittest"
    String integrationTestContext = "continuous-integration/jenkins/integrationtest"
    String pipInstallContext = "continuous-integration/jenkins/pipinstall"
    String bundleInstallContext = "continuous-integration/jenkins/bundleinstall"
    String swaggerContext = "continuous-integration/jenkins/swagger"
    String coverageContext = "continuous-integration/jenkins/coverage"
    String smokeContext = "continuous-integration/jenkins/e2e-smoke"
    String dbMigrateContext = "continuous-integration/jenkins/dbmigrate"
    String artifactsContext = "continuous-integration/jenkins/artifacts"
    String waitForFrontendContext = "continuous-integration/jenkins/waitforfrontend"

    String jenkinsSvcAccount = "jenkins"
    String defaultNameSpace = "jenkins"
    String defaultNodeImage = "docker-registry.default.svc:5000/jenkins/jenkins-slave-base-centos7-python36:latest"

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

    String jenkinsSlaveIqeImage = 'jenkins-slave-iqe:latest'
    String seleniumImage = 'selenium-fc29:latest'

    String defaultCloud = 'openshift'
    String defaultUICloud = 'upshift'
    String defaultUINameSpace = 'insights-qe-ci'

}
