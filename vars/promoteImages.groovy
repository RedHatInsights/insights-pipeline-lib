def call(parameters = [:]) {
    // images to copy from, e.g. ["myimage2:latest", "myimage3:other_tag"]
    srcImages = parameters['srcImages']
    // images to copy to, e.g. ["myimage2:prod", "myimage3:prod"]
    dstImages = parameters.get('dstImages')
    // where to pull source images from
    srcProject = parameters.get('srcProject', "buildfactory")
    srcCluster = parameters.get('srcCluster', pipelineVars.devCluster)
    // where to copy images to
    dstProject = parameters['dstProject']
    dstCluster = parameters.get('dstCluster', pipelineVars.prodCluster)
    // credentials to use
    srcSaUsername = parameters.get('srcSaUsername', "jenkins-deployer")
    srcSaTokenCredentialsId = parameters.get('srcSaTokenCredentialsId', "buildfactoryDeployerToken")
    dstSaUsername = parameters.get('dstSaUsername', "jenkins-deployer")
    dstSaTokenCredentialsId = parameters['dstSaTokenCredentialsId']

    if (!dstImages) dstImages = srcImages
    if (srcImages.size() != dstImages.size()) error("srcImages and dstImages lists are not the same size")

    srcRegistry = srcCluster.replace("api", "registry")
    dstRegistry = dstCluster.replace("api", "registry")
    imageFormat = "docker://%s/%s/%s"

    withCredentials(
        [
            string(credentialsId: srcSaTokenCredentialsId, variable: 'SRC_TOKEN'),
            string(credentialsId: dstSaTokenCredentialsId, variable: 'DST_TOKEN')
        ]
    ) {
        srcImages.eachWithIndex { srcImage, i ->
            srcImageUri = String.format(imageFormat, srcRegistry, srcProject, srcImage)
            dstImageUri = String.format(imageFormat, dstRegistry, dstProject, dstImages[i])
            sh "skopeo copy --src-creds=\"${srcSaUsername}:${SRC_TOKEN}\" --dest-creds=\"${dstSaUsername}:${DST_TOKEN}\" ${srcImageUri} ${dstImageUri}"
        }
    }
}
