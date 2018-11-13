// Context manager which notifies github if the operation succeeds or fails
// Example: withStatusContext.swagger { do swagger stuff }


private def dry(String context, Closure body) {
    // Don't Repeat Yourself...
    ghNotify context: context, status: "PENDING"

    try {
        body()
        ghNotify context: context, status: "SUCCESS"
    } catch (err) {
        echo err.getMessage()
        currentBuild.result = "UNSTABLE"
        ghNotify context: context, status: "FAILURE"
    }
}


def lint(Closure body) {
    dry(pipelineVars.lintContext, body)
}

def unitTest(Closure body) {
    dry(pipelineVars.unitTestContext, body)
}

def coverage(Closure body) {
    dry(pipelineVars.coverageContext, body)
}

def pipInstall(Closure body) {
    dry(pipelineVars.pipInstallContext, body)
}

def swagger(Closure body) {
    dry(pipelineVars.swaggerContext, body)
}

def smoke(Closure body) {
    dry(pipelineVars.smokeContext, body)
}
