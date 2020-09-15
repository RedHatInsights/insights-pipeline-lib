/**
 * Requires "Email Notifications Plugin"
 */
import groovy.transform.Field


def sendEmail(parameters = [:]) {
    def sentTo = parameters.get('sentTo', pipelineVars.emailDefaultSentTo)
    def replyTo = parameters.get('replyTo', pipelineVars.emailDefaultReplyTo)
    def subject = parameters.get('subject', pipelineVars.emailDefaultSubject)
    def content = parameters.get('content', pipelineVars.emailDefaultContent)

    println "sending to: $sentTo"



    if(sentTo != null && !sentTo.isEmpty()) {
      emailext(body: content, mimeType: 'text/html',
         replyTo: '$DEFAULT_REPLYTO', subject: subject,
         sentTo: sentTo, attachLog: true )
    }
}

