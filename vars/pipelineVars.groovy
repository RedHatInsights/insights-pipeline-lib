class pipelineVars implements Serializable {
    String lintContext = "continuous-integration/jenkins/lint"
    String unitTestContext = "continuous-integration/jenkins/unittest"
    String pipInstallContext = "continuous-integration/jenkins/pipinstall"
    String swaggerContext = "continuous-integration/jenkins/swagger"
    String coverageContext = "continuous-integration/jenkins/coverage"
    String smokeContext = "continuous-integration/jenkins/e2e-smoke"
    String jenkinsSvcAccount = "jenkins"
    String userPath = "~/.local/bin"
    String defaultNameSpace = "jenkins"
    String gitSshCreds = "insightsdroid-ssh-git"
    String smokeTestResourceLabel = "smoke_test_projects"
    String defaultNodeImage = "docker-registry.default.svc:5000/jenkins/jenkins-slave-base-centos7-python36:latest"
}
