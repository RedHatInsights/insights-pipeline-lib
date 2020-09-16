/**
 * Requires "Email Notifications Plugin"
 */

def sendEmail(args = [:]) {
    def to = args.get('to')
    def replyTo = args.get('replyTo')
    def subject = args.get('subject')
    def body = args.get('body')

    if(to != null && !to.isEmpty()) {
      emailext(body: body, mimeType: 'text/html',
         replyTo: replyTo, subject: subject,
         to: to, attachLog: true )
    }
}

