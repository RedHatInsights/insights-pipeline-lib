/**
 * Requires "Email Notifications Plugin"
 */

def sendEmail(parameters = [:]) {
        def to = parameters.get('to', pipelineVars.emailDefaultSentTo)
        def replyTo = parameters.get('replyTo', pipelineVars.emailDefaultReplyTo)
        def subject = parameters.get('subject', pipelineVars.emailDefaultSubject)
        def content = parameters.get('content', pipelineVars.emailDefaultContent)

        if(to != null && !to.isEmpty()) {
          emailext(body: content, mimeType: 'text/html',
             replyTo: replyTo, subject: subject,
             to: to, attachLog: true )
        }
}

