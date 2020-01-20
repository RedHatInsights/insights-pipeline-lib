def call(parameters = [:]) {
    def isTags = parameters['isTags']  // required param
    def jnlpImage = parameters.get('jnlpImage', "jenkins-deploy-jnlp:latest")
    def srcNamespace = parameters.get('srcNamespace', "buildfactory")
    def srcBaseUri = parameters.get(
        "srcBaseUri",
        "docker://registry.insights-dev.openshift.com/" + srcNamespace
    )
    def dstBaseUri = parameters.get("dstBaseUri", "docker://quay.io/cloudservices/")
    def srcTokenId = parameters.get("srcTokenId", "buildfactoryDeployerToken")
    def dstUser = parameters.get("dstUser", "cloudservices+push")
    def dstTokenId = parameters.get("dstTokenId", "quay-cloudservices-push-token")


    stage("Start jnlp container") {
        openShiftUtils.withJnlpNode(image: jnlpImage) {
            stage("Copy images") {
                isTags.each { isTag ->
                    sh "oc describe istag ${isTag}"

                    deployUtils.skopeoCopy(
                        srcUri: srcBaseUri + isTag
                        dstUri: dstBaseUri + isTag
                        srcUser: "na",
                        srcTokenId: srcTokenId,
                        dstUser: dstUser,
                        dstTokenId: dstTokenId
                    )
                }
            }
        }
    }
}