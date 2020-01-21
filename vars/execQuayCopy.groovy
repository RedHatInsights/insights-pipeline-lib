def call(parameters = [:]) {
    def imageName = parameters['imageName']  // required param
    def imageTag = parameters['imageTag']  // required param
    def dstImageName = parameters.get('dstImageName', imageName)
    def jnlpImage = parameters.get('jnlpImage', "jenkins-deploy-jnlp:latest")
    def srcNamespace = parameters.get('srcNamespace', "buildfactory")
    def srcBaseUri = parameters.get(
        "srcBaseUri",
        "docker://registry.insights-dev.openshift.com/" + srcNamespace + "/"
    )
    def dstBaseUri = parameters.get("dstBaseUri", "docker://quay.io/cloudservices/")
    def srcTokenId = parameters.get("srcTokenId", "buildfactoryDeployerToken")
    def dstUser = parameters.get("dstUser", "cloudservices+push")
    def dstTokenId = parameters.get("dstTokenId", "quay-cloudservices-push-token")

    def commitLabel = "io.openshift.build.commit.id"

    stage("Start jnlp container") {
        openShiftUtils.withJnlpNode(image: jnlpImage) {
            stage("Copy images") {
                def isTag = imageName + ":" + imageTag
                def commitId = sh(
                    script: (
                        "oc describe istag ${isTag} -n ${srcNamespace}" +
                        "| grep ${commitLabel} | cut -f2 -d'='"
                    )
                    returnStdout: true
                )
                def commitIsTag = imageName + ":" + commitId
                sh("oc tag ${isTag} ${commitIsTag} -n ${srcNamespace}")

                def tags = [dstImageName + ":" + imageTag, dstImageName + ":" + commitId]
                tags.each { tag ->
                    deployUtils.skopeoCopy(
                        srcUri: srcBaseUri + tag,
                        dstUri: dstBaseUri + tag,
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
