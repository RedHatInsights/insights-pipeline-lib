@Library("github.com/RedHatInsights/insights-pipeline-lib@master") _


def prepareRapidastStages(String ServiceName, String PluginName, String ApiScanner, String TargetUrl, String ApISpecUrl, String Jira, String Cloud=pipelineVars.upshiftCloud, String Namespace=pipelineVars.upshiftNameSpace) {
    openShiftUtils.withNode(cloud: Cloud, namespace: Namespace, image: 'quay.io/redhatproductsecurity/rapidast:2.7.0-rc1') {

        stage("Set Build Rapidast for ${ServiceName} service") {
             currentBuild.displayName = "#"+ env.BUILD_NUMBER + " " + "${ServiceName}"
        }

        stage("Prepare configs for ${ServiceName} Service") {
             parse_rapidast_options("${ServiceName}","${ApiScanner}","${TargetUrl}","${ApISpecUrl}")
        }

        stage("Run Rapidast for ${ServiceName} service") {
            catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                def secrets = [
                    [path: 'insights/secrets/qe/stage/swatch/rapidast_user', engineVersion: 2, secretValues: [
                    [envVar: 'RTOKEN', vaultKey: 'RTOKEN']]],
                ]
                def configuration = [vaultUrl: 'https://vault.devshift.net/',
                                         vaultCredentialId: 'vault-approle-cred',
                                         engineVersion: 1]
                withVault([configuration: configuration, vaultSecrets: secrets]) {
                    sh 'export RTOKEN=$RTOKEN'
                    sh "./rapidast.py --config config/config.yaml"
                }
            }
        }

        stage("Collect artifacts") {
            archiveArtifacts allowEmptyArchive: true, artifacts: "results/${ServiceName}/**/zap/*.*, , results.html, config/config.yaml"
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
                  plugin_name: "${PluginName}",
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

        stage("Create Jira tickets for alerts") {
            //Typecast Jira from String to Hashmap for easier usage
            jiraMap = StringToMap(Jira)
            if (jiraMap) {
                catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                    def sarif_file = findFiles(glob: "results/${ServiceName}/**/zap/zap-report.sarif.json")[0]
                    sh "git -c http.sslVerify=false clone https://gitlab.cee.redhat.com/fcanogab/sariftojira"
                    dir("sariftojira") {
                        withCredentials([string(credentialsId: 'JIRA_TOKEN', variable: 'JIRA_TOKEN')]) {
                            sh "export JIRA_TOKEN=${JIRA_TOKEN}"
                            jira_component = (jiraMap.Component == null) ? '' : "-jc ${jiraMap.Component}"
                            jira_labels =  (jiraMap.Labels == null) ? '' : "-jl ${jiraMap.Labels}"
                            sh "mv false_positives.json.example false_positives.json"
                            //Install dependencies python jira module via pip
                            echo "Installing pip and Jira module"
                            sh "python3 -m venv . && source bin/activate && pip install pyyaml jira"
                            sh "source bin/activate && python3 sarif_to_jira.py -p ${ServiceName} -t dast -s ../${sarif_file} -jp ${jiraMap.Project} -ja ${jiraMap.Assignee} ${jira_labels} ${jira_component} -u ${TargetUrl}"
                        }
                    }
                }
            }
            else {
                echo "Skipping Step for ${ServiceName} No Jira arguments configured"
            }
        }
    }
 }


def parse_rapidast_options(String ServiceName, String ApiScanner, String TargetUrl, String ApISpecUrl) {
    // Parse the options for rapidast and add it to the config file. Always pull the latest config file

    git url: 'https://github.com/RedHatProductSecurity/rapidast.git', branch: '2.7.0-rc1'
    def filename = 'tests/configmodel/older-schemas/v4.yaml'

    // Comment the fields not required.
    sh "sed -i 's/importUrlsFromFile:/# importUrlsFromFile:/' ${filename}"
    sh "sed -i 's/defectDojoExport:/# defectDojoExport:/' ${filename}"
    sh "sed -i 's/# format:/format:/' ${filename}"
    sh "sed -i 's/spiderAjax:/# spiderAjax:/' ${filename}"
    sh "sed -i 's/spider:/# spider:/' ${filename}"
    sh "sed -i 's/maxDuration:/# maxDuration:/g' ${filename}"
    sh "sed -i 's/browserId:/# browserId:/' ${filename}"
    sh "sed -i 's/url:/# url:/g' ${filename}"
    if ("${ApiScanner}" == "OpenApiScan") {
        echo "OpenAPI Spec Compliant API Scan selected"
        sh "sed -i 's/graphql:/# graphql:/' ${filename}"
        sh "sed -i 's/spiderAjax:/# spiderAjax:/' ${filename}"
        sh "sed -i 's/spider:/# spider:/' ${filename}"
        sh "sed -i 's/apiUrl:/apiUrl1:/' ${filename}"
        data = readYaml file: filename
        data.scanners.zap.apiScan.target = "${TargetUrl}"
        //Workaround for SWATCH-2347
        if ("${ServiceName}" == "CostManagement") {
            sh "redocly bundle ${ApISpecUrl} -o resolved-redocly.json"
            data.scanners.zap.apiScan.apis.apiFile = "resolved-redocly.json"
            data.scanners.zap.apiScan.apis.remove('apiUrl')
        }
        else if ("${ServiceName}" == "Host-Inventory") {
            echo "Using HBI workaround to clean the json for recursion"
            sh "curl --proxy squid.corp.redhat.com:3128 https://console.stage.redhat.com/api/inventory/v1/openapi.json -o test.json"
            sh "python3 utils/remove_openapi_ref_recursion.py -f test.json"
            data.scanners.zap.apiScan.apis.apiFile = "cleaned_openapi.json"
            data.scanners.zap.apiScan.apis.remove('apiUrl')
        }
        else {
            data.scanners.zap.apiScan.apis.remove('apiFile')
            data.scanners.zap.apiScan.apis.apiUrl = "${ApISpecUrl}"
        }
        if ("${ServiceName}" == "OcpVulnerability") {
            def policy = 'scanners/zap/policies/API-scan-minimal.policy'
            sh "sed -z -i 's|<p40018>\\n            <enabled>true|<p40018>\\n            <enabled>false|' ${policy}"
        }
    }
    else if ("${ApiScanner}" == "graphql") {
        sh "sed -i 's/apiScan:/# apiScan:/' ${filename}"
        sh "sed -i 's/target:/# target:/' ${filename}"
        sh "sed -i 's/apis:/# apis:/' ${filename}"
        sh "sed -i 's/apiUrl:/# apiUrl:/' ${filename}"
        sh "sed -i 's/# schemaUrl:/schemaUrl:/' ${filename}"
        data = readYaml file: filename
        data.scanners.zap.graphql.endpoint = "${TargetUrl}"
        data.scanners.zap.graphql.schemaUrl = "${ApISpecUrl}"
    }
    else {
        echo "Scanner not supported"
    }
    data.config.environ = ".env"
    data.application.shortName = "${ServiceName}"
    data.application.url = "${TargetUrl}"
    data.general.proxy.proxyHost = "squid.corp.redhat.com"
    data.general.proxy.proxyPort = "3128"
    data.general.authentication.parameters.client_id = "rhsm-api"
    data.general.authentication.parameters.token_endpoint = pipelineVars.stageSSOUrl
    data.general.container.type = "none"
    data.scanners.zap.passiveScan.disabledRules = "2,10015,10027,10054,10096,10024,10112"
    data.scanners.zap.miscOptions.oauth2OpenapiManualDownload = true
    //create new with updated YAML config
    writeYaml file: 'config/config.yaml', data: data
    echo "Configuration Value: " + data

}


def StringToMap(String JiraString) {
    if (JiraString == '[:]') {
        return [:] // Return an empty map if "[:]" is passed as input
    }
    JiraString = JiraString.replaceAll('\\[|\\]', '')
    def newMap = [:]
    JiraString.tokenize(',').each {
        kvTuple = it.tokenize(':')
        newMap[kvTuple[0].trim()] = kvTuple[1].trim()
    }
    return newMap
}
