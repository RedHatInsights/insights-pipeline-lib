@Library("github.com/RedHatInsights/insights-pipeline-lib@v5") _


def prepareRapidastStages(String ServiceName, String ApiScanner, String TargetUrl, String ApISpecUrl) {
    openShiftUtils.withNode(cloud: pipelineVars.upshiftCloud, image: 'quay.io/insights-qe/rapidast:multiuser') {

        stage("Set Build Rapidast for ${ServiceName} service") {
             currentBuild.displayName = "#"+ env.BUILD_NUMBER + " " + "${ServiceName}"
        }

        stage("Prepare configs for ${ServiceName} Service") {
            parse_rapidast_options("${ServiceName}","${ApiScanner}","${TargetUrl}","${ApISpecUrl}")
        }

        stage("Run Rapidast for ${ServiceName} service") {
            catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                 withCredentials([string(credentialsId: 'RTOKEN', variable: 'RTOKEN')]) {
                    sh "export RTOKEN=${RTOKEN}"
                    sh "./rapidast.py --log-level debug --config config/config.yaml"
                 }
            }
        }

        stage("Collect artifacts") {
            archiveArtifacts allowEmptyArchive: true, artifacts: "results/${ServiceName}/**/zap/*.*, , results.html"
        }
    }
 }


def parse_rapidast_options(String ServiceName, String ApiScanner, String TargetUrl, String ApISpecUrl) {
    // Parse the options for rapidast and add it to the config file. Always pull the latest config file

    git url: 'https://github.com/RedHatProductSecurity/rapidast.git', branch: 'development'
    def filename = 'config/config-template-long.yaml'

    // Comment the fields not required.
    sh "sed -i 's/importUrlsFromFile:/# importUrlsFromFile:/' ${filename}"
    sh "sed -i 's/defectDojoExport:/# defectDojoExport:/' ${filename}"
    sh "sed -i 's/# format:/format:/' ${filename}"
    if ("${ApiScanner}" == "OpenApiScan") {
        // Comment the fields not required.
        echo "OpenAPI Spec Compliant API Scan selected"
        sh "sed -i 's/graphql:/# graphql:/' ${filename}"
        sh "sed -i 's/spiderAjax:/# spiderAjax:/' ${filename}"
        sh "sed -i 's/spider:/# spider:/' ${filename}"
    }
    else {
        echo "Scanner not supported."
        currentBuild.result = 'FAILURE'
        sh "exit 1"
    }
    // Read the YAML and them populate the fields
    def data = readYaml file: filename
    data.config.environ = ".env"
    data.application.shortName = "${ServiceName}"
    data.application.url = "${TargetUrl}"
    data.general.proxy.proxyHost = "squid.corp.redhat.com"
    data.general.proxy.proxyPort = "3128"
    data.general.authentication.parameters.client_id = "rhsm-api"
    data.general.authentication.parameters.token_endpoint = pipelineVars.stageSSOUrl
    data.general.container.type = "none"
    data.scanners.zap.apiScan.target = "${TargetUrl}"
    data.scanners.zap.apiScan.apis.apiUrl = "${ApISpecUrl}"
    data.scanners.zap.miscOptions.oauth2OpenapiManualDownload = "True"

    //create new with updated YAML config
    writeYaml file: 'config/config.yaml', data: data
    echo "Configuration Value: " + data

}
