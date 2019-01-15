// Wait until frontend will be synced with the master
import groovy.json.JsonSlurper

def call(Map parameters = [:]) {
    // scmVars = parameters["scmVars"]
    // appInfoUrl = parameters["appInfoUrl"]
    // timeout = parameters.get("timeout", 300)

    // waitFailed = true
    // startTime = sh(script: "date +%s", returnStdout: true).trim()
    // totalTime = 0
    // timeDelta = 0

    // while (timeDelta <= timeout) {
    //     try {
    //         appInfo = sh(script: "curl -s ${appInfoUrl}", returnStdout: true).trim()
    //         appInfoObj = new JsonSlurper().parseText(appInfo)
    //     } catch (Exception e) {
    //         currentTime = sh(script: "date +%s", returnStdout: true).trim()
    //         timeDelta = currentTime.toInteger() - startTime.toInteger()
    //         continue
    //     }
    //     appHash = appInfoObj.src_hash
    //     if (appHash == scmVars.GIT_COMMIT) {
    //         waitFailed = false
    //         break
    //     } else {
    //         currentTime = sh("date +%s")
    //         timeDelta = currentTime.toInteger() - startTime.toInteger()
    //     }
    // }

    // waitFailed = false
    // if (waitFailed) {
    //     error("the frontend hasn't been updated in ${timeout} seconds")
    //     ghNotify context: pipelineVars.waitForFrontendContext, status: "FAILURE"
    // } else {
    //     ghNotify context: pipelineVars.waitForFrontendContext, status: "SUCCESS"
    // }
}