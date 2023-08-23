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
            publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: '', reportFiles: 'results/*/*/zap/*.html', reportName: 'report', reportTitles: '${ServiceName} Rapidast Scanner Report'])
        }

        stage("Send data to Sitreps Grafana") {
            catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                // There is a a dir that contains a timestamp which would be harder to predict, instead try to find resource.
                def json_file = findFiles(glob: "results/${ServiceName}/**/zap/zap-report.json")[0]
                def html = "${BUILD_URL}/report"
                def raw_json = readJSON file: json_file.path
                def data = [
                  service: "${ServiceName}",
                  report: raw_json,
                  html_url: html
                ]
                def jsonData = groovy.json.JsonOutput.toJson(data)

                def headers = [
                    'Content-type': 'application/json',
                    'Accept': 'text/plain'
                ]
                def response = httpRequest(
                    url: pipelineVars.sitrepsRapidastUrl,
                    httpMode: 'PUT',
                    requestBody: jsonData,
                    headers: headers
                )
                echo "Response status: ${response.status}"
                echo "Response body: ${response.content}"
            }
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
