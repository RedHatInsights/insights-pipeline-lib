// Context manager which notifies github if the operation succeeds or fails
// Example: withStatusContext.swagger { do swagger stuff }


private def dry(String context, Boolean shortenURL, Closure body) {
    // Don't Repeat Yourself...
    ghNotify context: context, shortenURL: shortenURL, status: "PENDING"

    try {
        body()
        ghNotify context: context, shortenURL: shortenURL, status: "SUCCESS"
    } catch (err) {
        echo err.getMessage()
        currentBuild.result = "UNSTABLE"
        ghNotify context: context, shortenURL: shortenURL, status: "FAILURE"
    }
}


def lint(Closure body) {
    dry(pipelineVars.lintContext, body)
}

def unitTest(Closure body) {
    dry(pipelineVars.unitTestContext, body)
}

def integrationTest(Closure body) {
    dry(pipelineVars.integrationTestContext, body)
}

def coverage(Closure body) {
    dry(pipelineVars.coverageContext, body)
}

def pipInstall(Closure body) {
    dry(pipelineVars.pipInstallContext, body)
}

def bundleInstall(Closure body) {
    dry(pipelineVars.bundleInstallContext, body)
}

def swagger(Closure body) {
    dry(pipelineVars.swaggerContext, body)
}

def smoke(Closure body) {
    dry(pipelineVars.smokeContext, body)
}

def dbMigrate(Closure body) {
    dry(pipelineVars.dbMigrateContext, body)
}


/**
 * You can define your own context here. If you don't want to reveal jenkins hostname set
 * shortenURL to true and job url will be replaced by url.corp.redhat.com shortener.
 */
def custom(String context, Boolean shortenURL = false, Closure body) {
    def customContext = "continuous-integration/jenkins/${context.toLowerCase().replaceAll('\\s','')}"
    dry(customContext, shortenURL, body)
}