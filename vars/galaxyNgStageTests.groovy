def call(args = [:]) {
    node {
        stage('Checkout galaxy_ng repo') {
            gitUtils.checkOutRepo(
                targetDir: "galaxy_ng",
                repoUrl: "https://github.com/ansible/galaxy_ng.git",
                credentialsId: "InsightsDroidGitHubHTTP",
                branch: "cloud_tests"
            )
        }
        stage('Run') {
            withCredentials([ string(credentialsId: 'vaultRoleId', variable: 'IQE_VAULT_ROLE_ID'), string(credentialsId: 'vaultSecretId', variable: 'IQE_VAULT_SECRET_ID')])
            {
                sh "IQE_VAULT_ROLE_ID=${IQE_VAULT_ROLE_ID} IQE_VAULT_SECRET_ID=${IQE_VAULT_SECRET_ID} ./galaxy_ng/dev/common/RUN_INTEGRATION_STAGE.sh"
            }
        }
        post {
            always {
                script{
                    archiveArtifacts allowEmptyArchive: true, artifacts: 'galaxy_ng-results.xml'
                    junit allowEmptyResults: true, testResults: 'galaxy_ng-results.xml'
                }
            }
        }
    }
}