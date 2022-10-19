
def call(args = [:]) {

    lockName = "${params.env}-test"
    lock(lockName) {
        parameters {
            password(name: 'TOKEN', defaultValue: 'secret', description: 'Github token')
        }

            stage('Checkout galaxy_ng repo') {
                    gitUtils.checkOutRepo(
                        targetDir: "galaxy_ng",
                        repoUrl: "git@github.com:ansible/galaxy_ng.git",
                        credentialsId: "InsightsDroidGitHubHTTP",
                        branch: "cloud_tests"
                    )
                
            }

            stage('Run') {
                    script {
                        sh "IQE_VAULT_GITHUB_TOKEN=${params.TOKEN} ./galaxy_ng/dev/common/RUN_INTEGRATION_STAGE.sh"
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
