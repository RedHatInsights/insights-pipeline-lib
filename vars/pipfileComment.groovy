// Comment (or remove the comments) on a github PR indicating that a Pipfile is in a bad state


def removeAll() {
    try {
        for (comment in pullRequest.comments) {
            if (comment.body.contains("Pipfile violation")) {
                deleteComment(comment.id)
            }
        }
    } catch (err) {
        msg = err.getMessage()
        echo "Error removing Pipfile comments: ${msg}"
    }
}


def post(commitId, str) {
    def shortId = commitId.substring(0, 7)
    def body = "Commit `${shortId}` Pipfile violation\n${str}"
    try {
        pullRequest.comment(body)
    } catch (err) {
        msg = err.getMessage()
        echo "Error adding Pipfile comment: ${msg}"
    }
}
