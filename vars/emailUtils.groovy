/**
 * Requires "Email Notifications Plugin"
 */

def sendEmail(args = [:]) {
        def to = args.get('to')
        def replyTo = args.get('replyTo')
        def subject = args.get('subject')
        def content = args.get('content')

        if(to != null && !to.isEmpty()) {
          emailext(body: content, mimeType: 'text/html',
             replyTo: replyTo, subject: subject,
             to: to, attachLog: true )
        }
}

