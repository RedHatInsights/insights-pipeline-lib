
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
            // vaultRoleId, vaultSecretId
            // params['vaultRoleIdCredential'] = pipelineVars.defaultVaultRoleIdCredential
            // params['vaultSecretIdCredential'] = pipelineVars.defaultVaultSecretIdCredential

            vaultParameters = clientUtils.setupVaultParameters()
            iqeUtils.writeVaultEnvVars(vaultParameters)
            script {
                // sh "IQE_VAULT_GITHUB_TOKEN=${params.TOKEN} ./galaxy_ng/dev/common/RUN_INTEGRATION_STAGE.sh"
                sh "./galaxy_ng/dev/common/RUN_INTEGRATION_STAGE.sh"
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