/**
 * Requires "Email Notifications Plugin"
 */

def sendEmail(args = [:]) {
        def to = args.get('to', pipelineVars.emailDefaultTo)
        def replyTo = args.get('replyTo', pipelineVars.emailDefaultReplyTo)
        def subject = args.get('subject', pipelineVars.emailDefaultSubject)
        def content = args.get('content', pipelineVars.emailDefaultContent)

        if(to != null && !to.isEmpty()) {
          emailext(body: content, mimeType: 'text/html',
             replyTo: replyTo, subject: subject,
             to: to, attachLog: true )
        }
}

