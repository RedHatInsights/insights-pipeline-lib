@SuppressWarnings([
    'CompileStatic',
    'UnusedVariable',
    'MethodSize',
    'ParameterCount',
    'NestedBlockDepth',
    'VariableName',
    'NoDef',
    'VariableTypeRequired',
    'DuplicateStringLiteral',
    'DuplicateNumberLiteral',
    'CouldBeSwitchStatement'
])
@Library('github.com/RedHatInsights/insights-pipeline-lib@master') _

String prepareRapidastStages(
    String serviceName,
    String pluginName,
    String apiScanner,
    String targetUrl,
    String apiSpecUrl,
    String jira,
    String cloud = pipelineVars.upshiftCloud,
    String namespace = pipelineVars.upshiftNameSpace,
    String vaultSecretPath = 'insights/secrets/qe/stage/swatch/rapidast_user'
) {
    openShiftUtils.withNode(
        cloud: cloud,
        namespace: namespace,
        image: 'quay.io/redhatproductsecurity/rapidast:2.12.1',
        resourceRequestMemory: '1Gi',
        resourceLimitMemory: '4Gi'
    ) {
        String buildFailureResult = 'FAILURE'
        String buildSuccessResult = 'SUCCESS'
        String buildUnstableResult = 'UNSTABLE'
        String rtokenEnvVar = 'RTOKEN'
        String vaultUrlValue = 'https://vault.devshift.net/'
        String vaultCredId = 'vault-approle-cred'
        String jiraTokenId = 'JIRA_TOKEN'
        String separatorLine = '======================================================='
        String warnNewPattern = /WARN-NEW:\s*(\d+)/
        int zeroValue = 0

        stage("Set Build Rapidast for ${serviceName} service") {
            currentBuild.displayName = '#' + env.BUILD_NUMBER + ' ' + serviceName
        }

        stage("Prepare configs for ${serviceName} Service") {
            parseRapidastOptions(serviceName, apiScanner, targetUrl, apiSpecUrl)
        }

        stage("Run Rapidast for ${serviceName} service") {
            catchError(buildResult: buildFailureResult, stageResult: buildFailureResult) {
                List<Map<String, Object>> secrets = [
                    [
                        path: vaultSecretPath,
                        engineVersion: 2,
                        secretValues: [
                            [envVar: rtokenEnvVar, vaultKey: rtokenEnvVar]
                        ]
                    ],
                ]
                Map<String, Object> configuration = [
                    vaultUrl: vaultUrlValue,
                    vaultCredentialId: vaultCredId,
                    engineVersion: 1
                ]
                withVault([configuration: configuration, vaultSecrets: secrets]) {
                    sh 'export RTOKEN=$RTOKEN'

                    String yamlConfigFile = sh(
                        returnStdout: true,
                        script: 'cat ./config/config.yaml'
                    )
                    echo "Display content of config file: ./config/config.yaml: \n\n${yamlConfigFile}"

                    String rapidastScript = "${pipelineVars.rapidastBinDirectory}/rapidast.py"
                    String rapidastCmd = "${rapidastScript} --log-level " +
                        "${pipelineVars.rapidastLogLevel} --config ./config/config.yaml && echo \$?"
                    String resultsRapidasat = sh(returnStdout: true, script: rapidastCmd)

                    splLines = resultsRapidasat.split('\n')
                    String cmdStatus = splLines[-1]

                    if (cmdStatus.toInteger() != zeroValue) {
                        echo separatorLine
                        echo "rapidast command failed with error message ${resultsRapidasat}"
                        echo separatorLine
                    }
                    else {
                        echo "STDOUT: ${resultsRapidasat}"
                    }

                    splLines.each { String line ->
                        if (line =~ warnNewPattern) {
                            warnNewFound = true
                            warnNewCount = (line =~ warnNewPattern)[zeroValue][1]
                        }
                    }

                    if (warnNewFound) {
                        if (warnNewCount == String.valueOf(zeroValue)) {
                            echo 'WARN-NEW is 0.'
                        } else {
                            String slackMessage = ("""
                                WARN-NEW is not 0.
                                ${separatorLine}
                                rapidast command for ${serviceName} completed successfully, but warnings were raised.
                                Because of this, the build was marked as UNSTABLE.
                                Please investigate output of rapidast command.
                                ${separatorLine}""".stripIndent())
                            env.slackMessage = slackMessage

                            echo slackMessage
                            currentBuild.result = buildUnstableResult
                        }
                    } else {
                        echo 'WARN-NEW not found in the output.'
                    }
                }
            }
        }

        stage('Collect artifacts') {
            String artifactsPattern = "results/${serviceName}/**/zap/*.*, , results.html, config/config.yaml"
            archiveArtifacts allowEmptyArchive: true, artifacts: artifactsPattern
            publishHTML([
                allowMissing: true,
                alwaysLinkToLastBuild: false,
                keepAll: true,
                reportDir: '',
                reportFiles: 'results/*/*/zap/*.html',
                reportName: 'report',
                reportTitles: "${serviceName} Rapidast Scanner Report"
            ])
        }

        stage('Send data to Sitreps Grafana') {
            catchError(buildResult: buildSuccessResult, stageResult: buildFailureResult) {
                Object jsonFile = findFiles(glob: "results/${serviceName}/**/zap/zap-report.json")[zeroValue]
                String html = "${BUILD_URL}/report"
                Object rawJson = readJSON file: jsonFile.path
                Map<String, Object> data = [
                  service: serviceName,
                  plugin_name: pluginName,
                  report: rawJson,
                  html_url: html
                ]
                String jsonData = groovy.json.JsonOutput.toJson(data)

                Map<String, String> headers = [
                    'Content-type': 'application/json',
                    'Accept': 'text/plain'
                ]
                Object response = httpRequest(
                    url: pipelineVars.sitrepsRapidastUrl,
                    httpMode: 'PUT',
                    requestBody: jsonData,
                    headers: headers
                )
                echo "Response status: ${response.status}"
                echo "Response body: ${response.content}"
            }
        }

        stage('Create Jira tickets for alerts') {
            Map<String, String> jiraMap = stringToMap(jira)
            if (jiraMap) {
                catchError(buildResult: buildSuccessResult, stageResult: buildFailureResult) {
                    Object sarifFile = findFiles(
                        glob: "results/${serviceName}/**/zap/zap-report.sarif.json"
                    )[zeroValue]
                    String cloneUrl = 'https://gitlab.cee.redhat.com/fcanogab/sariftojira'
                    sh "git -c http.sslVerify=false clone ${cloneUrl}"
                    dir('sariftojira') {
                        withCredentials([
                            string(credentialsId: jiraTokenId, variable: jiraTokenId)
                        ]) {
                            withEnv(['JIRA_EMAIL=insights-qe-jira-bot@redhat.com']) {
                                String jiraComponent = (jiraMap.Component == null) ?
                                    '' : "-jc ${jiraMap.Component}"
                                String jiraLabels =  (jiraMap.Labels == null) ?
                                    '' : "-jl ${jiraMap.Labels}"
                                sh 'mv false_positives.json.example false_positives.json'
                                echo 'Installing pip and Jira module'
                                sh 'python3 -m venv . && source bin/activate && pip install pyyaml jira'
                                String sarifToJiraCmd = "source bin/activate && python3 sarif_to_jira.py " +
                                    "-p ${serviceName} -t dast -s ../${sarifFile} " +
                                    "-jp ${jiraMap.Project} -ja ${jiraMap.Assignee} " +
                                    "${jiraLabels} ${jiraComponent} -u ${targetUrl}"
                                sh sarifToJiraCmd
                            }
                        }
                    }
                }
            }
            else {
                echo "Skipping Step for ${serviceName} No Jira arguments configured"
            }
        }

        stage('Send slack message if build is UNSTABLE') {
            if (currentBuild.result == buildUnstableResult) {
                Map<String, String> jiraMap = stringToMap(jira)
                if (jiraMap) {
                    if (jiraMap.Assignee != null) {
                        slackUtils.sendMsg([
                            msg: env.slackMessage,
                            slackChannel: '@' + jiraMap.Assignee,
                            slackTokenCredentialId: 'slackToken'
                        ])
                    }
                }
                else {
                    echo 'Assignee key/value pair not defined in groovy file.'
                }
            }
        }
    }
    return buildSuccessResult
}

void parseRapidastOptions(String serviceName, String apiScanner, String targetUrl, String apiSpecUrl) {
    String vaultUrlValue = 'https://vault.devshift.net/'
    String vaultCredId = 'vault-approle-cred'
    String gcsKeyValue = 'gcs_key'
    String apiUrlKey = 'apiUrl'
    String graphqlValue = 'graphql'

    List<Map<String, Object>> secrets = [
        [
            path: 'insights/secrets/qe/global/rapidast-sa-insights_key',
            engineVersion: 2,
            secretValues: [
                [envVar: gcsKeyValue, vaultKey: gcsKeyValue]
            ]
        ],
    ]
    Map<String, Object> configuration = [
        vaultUrl: vaultUrlValue,
        vaultCredentialId: vaultCredId,
        engineVersion: 1
    ]
    withVault([configuration: configuration, vaultSecrets: secrets]) {
        writeFile file: 'gcs-key.json', text: env."${gcsKeyValue}"
        sh 'chmod 600 gcs-key.json'

        String rapidastConfigTemplate = """
            config:
                configVersion: 6
                base_results_dir: ./results
                environ:
                    envFile: .env
                googleCloudStorage:
                    keyFile: "gcs-key.json"
                    bucketName: "${pipelineVars.rapidastBucket}"
                    directory: "insights/${serviceName}"

            application:
                shortName: "${serviceName}"
                url: "${targetUrl}"

            general:
                proxy:
                    proxyHost: "${pipelineVars.rapidastProxyHost}"
                    proxyPort: "${pipelineVars.rapidastProxyPort}"
                authentication:
                    type: oauth2_rtoken
                    parameters:
                        client_id: rhsm-api
                        token_endpoint: "${pipelineVars.stageSSOUrl}"
                        rtoken_from_var: RTOKEN

            scanners:
                zap:
                    apiScan:
                        target: "${targetUrl}"
                        apis:
                            apiUrl: "${apiSpecUrl}"
                    graphql:
                        endpoint: "${targetUrl}"
                        schemaUrl: "${apiSpecUrl}"
                    passiveScan:
                        disabledRules: 2,10015,10027,10054,10096,10024,10112
                    activeScan:
                        policy: API-scan-minimal
                    report:
                        format: ["json","html","sarif"]
                    miscOptions:
                        oauth2ManualDownload: true
        """

        Object data = readYaml text: rapidastConfigTemplate
        if (apiScanner == 'OpenApiScan') {
            echo 'OpenAPI Spec Compliant API Scan selected'

            data.scanners.zap.remove(graphqlValue)

            if (serviceName == 'CostManagement') {
                sh "redocly bundle ${apiSpecUrl} -o resolved.redocly.json"
                data.scanners.zap.apiScan.apis.apiFile = 'resolved.redocly.json'
                data.scanners.zap.apiScan.apis.remove(apiUrlKey)
            }
            else if (serviceName == 'Host-Inventory') {
                echo 'Using HBI workaround to clean the json for recursion'
                String curlCmd = 'curl --proxy squid.corp.redhat.com:3128 ' +
                    'https://console.stage.redhat.com/api/inventory/v1/openapi.json -o test.json'
                sh curlCmd
                String pythonCmd = "python3 ${pipelineVars.rapidastBinDirectory}/" +
                    'utils/remove_openapi_ref_recursion.py -f test.json'
                sh pythonCmd
                data.scanners.zap.apiScan.apis.apiFile = 'cleaned_openapi.json'
                data.scanners.zap.apiScan.apis.remove(apiUrlKey)
            }
            if (serviceName == 'OcpVulnerability') {
                String policy = 'scanners/zap/policies/API-scan-minimal.policy'
                String sedCmd = "sed -z -i 's|<p40018>\\n            <enabled>true|" +
                    "<p40018>\\n            <enabled>false|' ${policy}"
                sh sedCmd
            }
        }
        else if (apiScanner == graphqlValue) {
            echo 'GraphQL API Scan selected'

            data.scanners.zap.remove('apiScan')
        }
        else {
            echo "Scanner '${apiScanner}' not supported!"
            String errorMsg = "Unsupported scanner type '${apiScanner}'. " +
                "Only 'OpenApiScan' and 'graphql' are supported."
            error(errorMsg)
        }

        // Create configuration file from the YAML config
        writeYaml file: 'config/config.yaml', data:data
        echo 'Configuration Value: ' + data
    }
}

Map<String, String> stringToMap(String jiraString) {
    if (jiraString == '[:]') {
        return [:]
    }
    String cleanedString = jiraString.replaceAll('\\[|\\]', '')
    Map<String, String> newMap = [:]
    cleanedString.tokenize(',').each { String item ->
        List<String> kvTuple = item.tokenize(':')
        newMap[kvTuple[0].trim()] = kvTuple[1].trim()
    }
    return newMap
}
