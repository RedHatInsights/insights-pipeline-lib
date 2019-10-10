/**
 * Library to slackNotify execute status in Slack
 * @param stage - stage can be specified here if some exception happened
 * @param channel - name of slack channel
 * @param message - message
 * @param baseUrl - slack baseUrl
 * @return
 * to run this method successfully on your Jenkins master need to configure
 * Global Slack Notifier Settings in jenkins -> Configure
 * and have installed "Slack Notification Plugin" on your Jenkins master
 */
def call(Map parameters = [:]){
    def stage = parameters.get("stage", "default_stage")
	def channel = parameters.get("channel", "insights-qe-feed")
    def message = parameters.get("message", "")
    def baseUrl = parameters.get("baseUrl", "https://ansible.slack.com/services/hooks/jenkins-ci/")

    def color_map = ['SUCCESS': 'good', 'FAILURE': 'danger', 'UNSTABLE': 'warning', 'ABORTED': 'danger']
    def default_message = ['SUCCESS': "tests have passed",
                           'FAILURE': "Failed! OOPS, Something wrong with pipeline",
                           'UNSTABLE': "tests have failed",
                           'ABORTED': "Current build have been aborted"]

    current_result = (currentBuild.result != null) ? currentBuild.result : currentBuild.currentResult
    slackSend ( baseUrl: baseUrl,
                botUser: true,
                channel: channel,
                color: color_map[current_result],
                message: "${message}; ${default_message[current_result]}; " +
                         "stage: ${stage};  " +
                         "build: ${env.BUILD_URL}")
}
