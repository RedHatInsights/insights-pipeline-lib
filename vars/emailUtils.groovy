/**
 * Requires "Email Notifications Plugin"
 */
//
// import groovy.transform.Field
// import hudson.AbortException

private def sendEmail(sentTo, replyTo, subject, content) {
        if(sentTo != null && !sentTo.isEmpty()) {
          emailext(
            to: sentTo,
            subject: subject,
            body: content,
            mimeType: 'text/html',
            replyTo: replyTo,
            attachLog: true
          )
        }
}


def call(parameters = [:]) {
        def sentTo = parameters.get('sentTo', pipelineVars.emailDefaultSentTo)
        def replyTo = parameters.get('replyTo', pipelineVars.emailDefaultReplyTo)
        def subject = parameters.get('subject', pipelineVars.emailDefaultSubject)
        def content = parameters.get('content', pipelineVars.emailDefaultContent)
        def extraJobProperties = parameters.get('extraJobProperties', [])

//         def jobProperties = []
//         jobProperties.addAll(extraJobProperties)
//         properties(jobProperties)

        sendEmail(sentTo, replyTo, subject, content, replyTo)
}

