def call(args = [:]) {
    podTemplate(containers: [
    containerTemplate(name: 'galaxy', image: 'registry.access.redhat.com/openshift3/jenkins-slave-base-rhel7:v3.11')]){
        node(){
            stage('Checkout galaxy_ng repo') {
                container('galaxy'){
                    gitUtils.checkOutRepo(
                        targetDir: "galaxy_ng",
                        repoUrl: "https://github.com/ansible/galaxy_ng.git",
                        credentialsId: "InsightsDroidGitHubHTTP",
                        branch: "master"
                    )
                }
            }
            stage('Run integration tests against stage') {
                container('galaxy'){
                    withCredentials([ string(credentialsId: 'vaultRoleId', variable: 'IQE_VAULT_ROLE_ID'), string(credentialsId: 'vaultSecretId', variable: 'IQE_VAULT_SECRET_ID')])
                    {
                        sh ( script: "IQE_VAULT_ROLE_ID=${IQE_VAULT_ROLE_ID} IQE_VAULT_SECRET_ID=${IQE_VAULT_SECRET_ID} ./galaxy_ng/dev/common/RUN_INTEGRATION_STAGE.sh", returnStatus: true)
                        archiveArtifacts allowEmptyArchive: true, artifacts: 'galaxy_ng-results.xml'
                        junit allowEmptyResults: true, testResults: 'galaxy_ng-results.xml'
                    }
                }
            }
        }
    }
}
