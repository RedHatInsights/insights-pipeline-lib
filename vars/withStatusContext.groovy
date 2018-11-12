// Context manager which notifies github if the operation succeeds or fails
// Example: withStatusContext.swagger { do swagger stuff }
import com.redhat.insights_pipeline.constants.Const;


private def dry(String context, Closure body) {
    // Don't Repeat Yourself...
    ghNotify context: context, "PENDING"

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
    dry(Const.lintContext, body)
}

def unitTest(Closure body) {
    dry(Const.unitTestContext, body)
}

def coverage(Closure body) {
    dry(Const.coverageContext, body)
}

def pipInstall(Closure body) {
    dry(Const.pipInstallContext, body)
}

def swagger(Closure body) {
    dry(Const.swaggerContext, body)
}

def smoke(Closure body) {
    dry(Const.smokeContext, body)
}
