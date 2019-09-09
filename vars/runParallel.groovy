// A wrapper around the 'parallel' method that tracks the pass/fail state of the individual parallel stages

// Note that this executes the parallel run in a catchError block and therefore will only set the build
// status to 'failed', it will NOT halt the execution of the build if a stage failed! Therefore, you should
/// use this method's return value and check if the list of failed stages is empty, e.g.:

// if (!returnValue["failed"].isEmpty()) { error("A parallel stage failed!") }


def call(Map<String, Closure> map) {
    def successStages = []
    def failedStages = []
    
    def newMap = [:]

    map.each { stageName, stageClosure ->
        def newClosure = {
            try {
                stageClosure()
                successStages.add(stageName)
            } catch (err) {
                failedStages.add(stageName)
                throw err
            }
        }
        newMap[stageName] = newClosure
    }

    catchError {
        parallel(newMap)
    }

    return ["success": successStages, "failed": failedStages]
}