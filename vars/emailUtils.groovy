/**
 * Requires "Email Notifications Plugin"
 */

private def sendEmail(sentTo, replyTo, subject, content) {

        println "sending to: $sentTo"

        if(sentTo != null && !sentTo.isEmpty()) {
          emailext(body: content, mimeType: 'text/html',
             replyTo: '$DEFAULT_REPLYTO', subject: subject,
             to: sentTo, attachLog: true )
        }
}


def call(parameters = [:]) {
        def sentTo = parameters.get('sentTo', pipelineVars.emailDefaultSentTo)
        def replyTo = parameters.get('replyTo', pipelineVars.emailDefaultReplyTo)
        def subject = parameters.get('subject', pipelineVars.emailDefaultSubject)
        def content = parameters.get('content', pipelineVars.emailDefaultContent)
        def extraJobProperties = parameters.get('extraJobProperties', [])

        def jobProperties = []
        jobProperties.addAll(extraJobProperties)
        properties(jobProperties)

        sendEmail(sentTo, replyTo, subject, content)
}