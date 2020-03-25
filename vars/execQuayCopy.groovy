def call(parameters = [:]) {
    timeout(time: 10, unit: "MINUTES") {
        run(parameters)
    }
}


def run(parameters) {
    def imageName = parameters['imageName']  // required param
    def imageTag = parameters['imageTag']  // required param
    def dstImageName = parameters.get('dstImageName', imageName)
    def dstImageTag = parameters.get('dstImageTag', imageTag)
    def jnlpImage = parameters.get('jnlpImage', "jenkins-deploy-jnlp:latest")
    def srcNamespace = parameters.get('srcNamespace', "buildfactory")
    def srcBaseUri = parameters.get(
        "srcBaseUri",
        "docker://registry.insights-dev.openshift.com/${srcNamespace}"
    )
    def dstBaseUri = parameters.get("dstBaseUri", "docker://${pipelineVars.quayBaseUri}")
    def srcTokenId = parameters.get("srcTokenId", "buildfactoryDeployerToken")
    def dstUser = parameters.get("dstUser", pipelineVars.quayUser)
    def dstTokenId = parameters.get("dstTokenId", pipelineVars.quayPushCredentialsId)
    def copyCommitTag = parameters.get("copyCommitTag", true)
    def extraDstTags = parameters.get("extraDstTags", [])

    def commitLabel = "io.openshift.build.commit.id"

    stage("Start jnlp container") {
        openShiftUtils.withJnlpNode(image: jnlpImage) {
            stage("Copy images") {
                def srcIsTag = imageName + ":" + imageTag
                def dstTags = [dstImageName + ":" + dstImageTag]

                if (copyCommitTag) {
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
                    dstTags.add(dstImageName + ":" + commitId)
                }

                extraDstTags.each { dstTag ->
                    dstTags.add(dstImageName + ":" + dstTag)
                }

                dstTags.each { dstTag ->
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
