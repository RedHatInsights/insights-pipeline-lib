/**
 * Requires "Slack Notification Plugin"
 */
import groovy.transform.Field


// Map jenkins build result to a slack message color
@Field def colorMap = [
    'success': 'good', 'failure': 'danger', 'unstable': 'warning', 'aborted': 'danger'
]

// Displays a shortened hyperlink to the job
@Field def buildLink = "<${env.RUN_DISPLAY_URL}|${env.JOB_NAME} #${env.BUILD_NUMBER}>"

@Field def msgPrefix = [
    'success': ":heavy_check_mark: ${buildLink}",
    'failure': ":static_rotating_light: ${buildLink}",
    'unstable': ":static_rotating_light: ${buildLink}",
    'aborted': ":static_rotating_light: ${buildLink}",
    'info': ":information_source: ${buildLink}"
]

// Map jenkins build result to default slack message content
@Field def defaultMsgMap = [
    'success': "job succeeded",
    'failure': "job failed",
    'unstable': "job unstable",
    'aborted': "job aborted"
]


private def currentResult() {
    if (currentBuild.result != null) return currentBuild.result.toLowerCase()
    else return currentBuild.currentResult.toLowerCase()
}


def defaultColor() {
    return colorMap[currentResult()]

}


def defaultMsg() {
    return defaultMsgMap[currentResult()]
}


def sendMsg(parameters = [:]) {
    def slackChannel = parameters.get('slackChannel', pipelineVars.slackDefaultChannel)
    def slackUrl = parameters.get('slackUrl', pipelineVars.slackDefaultUrl)
    def slackTeamDomain = parameters.get('slackTeamDomain')
    def slackTokenCredentialId = parameters.get('slackTokenCredentialId')
    // check if the value passed in for the parameter was 'null'
    if (!slackChannel) slackChannel = pipelineVars.slackDefaultChannel
    if (!slackUrl) slackUrl = pipelineVars.slackDefaultUrl

    def msg = parameters.get('msg')
    def color = parameters.get('color')
    def stage = parameters.get('stage')
    def result = parameters.get('result', currentResult()).toLowerCase()

    txt = msgPrefix[result]
    txt += stage ? " stage: ${stage} " : " "
    txt += msg ? msg : defaultMsg()

    if (slackChannel instanceof String) slackChannel = [slackChannel]

    slackChannel.each { channel ->
        slackSend(
            teamDomain: slackTeamDomain,
            tokenCredentialId: slackTokenCredentialId,
            baseUrl: slackUrl,
            botUser: true,
            channel: channel,
            color: color ? color : colorMap[result],
            message: txt
        )
    }
}
