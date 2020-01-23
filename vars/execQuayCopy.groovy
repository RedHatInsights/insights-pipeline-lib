def call(parameters = [:]) {
    def imageName = parameters['imageName']  // required param
    def imageTag = parameters['imageTag']  // required param
    def dstImageName = parameters.get('dstImageName', imageName)
    def dstImageTag = parameters.get('dstImageTag', imageTag)
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
                def srcIsTag = imageName + ":" + imageTag
                def commitId = sh(
                    script: (
                        "oc describe istag ${srcIsTag} -n ${srcNamespace}" +
                        "| grep ${commitLabel} | cut -f2 -d'='"
                    ),
                    returnStdout: true
                )
                // trim commit hash to 7 chars
                commitId = commitId[0..6]
                def commitIsTag = imageName + ":" + commitId
                sh("oc tag ${srcIsTag} ${commitIsTag} -n ${srcNamespace}")

                def tags = [dstImageName + ":" + dstImageTag, dstImageName + ":" + commitId]
                tags.each { dstTag ->
                    deployUtils.skopeoCopy(
                        srcUri: srcBaseUri + srcIsTag,
                        dstUri: dstBaseUri + dstTag,
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
