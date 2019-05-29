// This is a small improvement to reduce nested calls.
//
// Before:
// stage("Run-integration-tests") {
//     withStatusContext.custom(env.STAGE_NAME, true) {
//         body()
//     }
// }
//
// After:
// stageWithContext("Run-integration-tests") {
//     body()   
// }


def call(String name, Boolean shortenURL = true, Closure body) {
    stage(name) {
        withStatusContext.custom(name, shortenURL) {
            body()
        }
    }
}
