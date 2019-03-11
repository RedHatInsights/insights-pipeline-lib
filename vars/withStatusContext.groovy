// Context manager which notifies github if the operation succeeds or fails
// Example: withStatusContext.swagger { do swagger stuff }


private def dry(String context, Boolean shortenURL, Closure body) {
    // Don't Repeat Yourself...
    ghNotify context: context, shortenURL: shortenURL, status: "PENDING"

    try {
        body()
        ghNotify context: context, shortenURL: shortenURL, status: "SUCCESS"
    } catch (err) {
        echo err.toString()
        echo err.getMessage()
        try {
            def trace = err.getStackTrace() as String[]
            echo trace.join('\n')
        } catch (innerErr) {
            echo innerErr.toString()
        } finally {
            currentBuild.result = "UNSTABLE"
            ghNotify context: context, shortenURL: shortenURL, status: "FAILURE"
        }
    }
}


def lint(Boolean shortenURL = false, Closure body) {
    dry(pipelineVars.lintContext, shortenURL, body)
}

def unitTest(Boolean shortenURL = false, Closure body) {
    dry(pipelineVars.unitTestContext, shortenURL, body)
}

def integrationTest(Boolean shortenURL = false, Closure body) {
    dry(pipelineVars.integrationTestContext, shortenURL, body)
}

def coverage(Boolean shortenURL = false, Closure body) {
    dry(pipelineVars.coverageContext, shortenURL, body)
}

def pipInstall(Boolean shortenURL = false, Closure body) {
    dry(pipelineVars.pipInstallContext, shortenURL, body)
}

def bundleInstall(Boolean shortenURL = false, Closure body) {
    dry(pipelineVars.bundleInstallContext, shortenURL, body)
}

def swagger(Boolean shortenURL = false, Closure body) {
    dry(pipelineVars.swaggerContext, shortenURL, body)
}

def smoke(Boolean shortenURL = false, Closure body) {
    dry(pipelineVars.smokeContext, shortenURL, body)
}

def dbMigrate(Boolean shortenURL = false, Closure body) {
    dry(pipelineVars.dbMigrateContext, shortenURL, body)
}

def artifacts(Boolean shortenURL = false, Closure body) {
    dry(pipelineVars.artifactsContext, shortenURL, body)
}

/**
 * You can define your own context here. If you don't want to reveal jenkins hostname set
 * shortenURL to true and job url will be replaced by url.corp.redhat.com shortener.
 */
def custom(String context, Boolean shortenURL = false, Closure body) {
    def customContext = "continuous-integration/jenkins/${context.toLowerCase().replaceAll('\\s','')}"
    dry(customContext, shortenURL, body)
}
