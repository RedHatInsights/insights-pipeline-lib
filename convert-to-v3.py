"""
Run this script to do an in-place conversion of a pipeline job that is currently using
insights-pipeline-lib<v3 to convert it to v3. Note that this will replace the contents of the
file, so take a backup or ensure that you can revert changes (e.x. via git) on the file if
necessary.

$ python convert-to-v3.py /path/to/Jenkinsfile
"""

import os
import sys


# We could use regex matching but at the moment building the regex strings would take more
# time than it took to compile this list the "easy way"...
replacements = {
    "cancelPriorBuilds": "pipelineUtils.cancelPriorBuilds",
    "changedFiles": "gitUtils.getFilesChanged",
    "checkCoverage": "pythonUtils.checkCoverage",
    "checkOutRepo": "gitUtils.checkOutRepo",
    "deployHelpers": "deployUtils",
    "deployServiceSet": "deployUtils.deployServiceSet",
    "deploymentPipeline": "execDeployPipeline",
    "getFilesChanged": "gitUtils.getFilesChanged",
    "ghNotify": "gitUtils.ghNotify",
    "openShift": "openShiftUtils",
    "pipelineVars.defaultUICloud": "pipelineVars.upshiftCloud",
    "pipelineVars.defaultUINameSpace": "pipelineVars.upshiftNameSpace",
    "pipfileComment.post": "pythonUtils.postPipfileComment",
    "pipfileComment.removeAll": "pythonUtils.removePipfileComments",
    "promoteImages": "deployUtils.promoteImages",
    "runBundleInstall": "rubyUtils.runBundleInstall",
    "runIfMasterOrPullReq": "pipelineUtils.runIfMasterOrPullReq",
    "runParallel": "pipelineUtils.runParallel",
    "runPipenvInstall": "pythonUtils.runPipenvInstall",
    "runPythonLintCheck": "pythonUtils.runLintCheck",
    "runSmokeTest": "execSmokeTest",
    "slackNotify": "slack.sendMsg",
    "stageWithContext": "gitUtils.stageWithContext",
    "triggeredByComment": "pipelineUtils.triggeredByComment",
    "waitForDeployment": "deployUtils.waitForDeployment",
    "withStatusContext.lint": 'gitUtils.withStatusContext("lint")',
    "withStatusContext.unitTest": 'gitUtils.withStatusContext("unittest")',
    "withStatusContext.integrationTest": 'gitUtils.withStatusContext("integrationtest")',
    "withStatusContext.coverage": 'gitUtils.withStatusContext("coverage")',
    "withStatusContext.pipInstall": 'gitUtils.withStatusContext("pipinstall")',
    "withStatusContext.bundleInstall": 'gitUtils.withStatusContext("bundleinstall")',
    "withStatusContext.swagger": 'gitUtils.withStatusContext("swagger")',
    "withStatusContext.smoke": 'gitUtils.withStatusContext("smoke")',
    "withStatusContext.dbMigrate": 'gitUtils.withStatusContext("dbmigrate")',
    "withStatusContext.artifacts": 'gitUtils.withStatusContext("artifacts")',
    "withStatusContext.waitForFrontend": 'gitUtils.withStatusContext("waitforfrontend")',
    "withStatusContext.lint(": 'gitUtils.withStatusContext("lint", ',
    "withStatusContext.unitTest(": 'gitUtils.withStatusContext("unittest", ',
    "withStatusContext.integrationTest(": 'gitUtils.withStatusContext("integrationtest", ',
    "withStatusContext.coverage(": 'gitUtils.withStatusContext("coverage", ',
    "withStatusContext.pipInstall(": 'gitUtils.withStatusContext("pipinstall", ',
    "withStatusContext.bundleInstall(": 'gitUtils.withStatusContext("bundleinstall", ',
    "withStatusContext.swagger(": 'gitUtils.withStatusContext("swagger", ',
    "withStatusContext.smoke(": 'gitUtils.withStatusContext("smoke", ',
    "withStatusContext.dbMigrate(": 'gitUtils.withStatusContext("dbmigrate", ',
    "withStatusContext.artifacts(": 'gitUtils.withStatusContext("artifacts", ',
    "withStatusContext.waitForFrontend(": 'gitUtils.withStatusContext("waitforfrontend", ',
    "withStatusContext.custom(": "gitUtils.withStatusContext(",
    '@Library("github.com/RedHatInsights/insights-pipeline-lib")': (
        '@Library("github.com/RedHatInsights/insights-pipeline-lib@v3")'
    ),
    "@Library('github.com/RedHatInsights/insights-pipeline-lib')": (
        "@Library('github.com/RedHatInsights/insights-pipeline-lib')"
    )
}


try:
    filename = os.path.abspath(sys.argv[1])
except IndexError:
    print("You didn't provide a filename")
    sys.exit(1)


with open(filename, "r") as readfile, open(f"{filename}-new", "w") as writefile:
    for lineno, line in enumerate(readfile):
        changed = False
        orig_line = line
        for orig, new in replacements.items():
            if orig in line:
                changed = True
                line = line.replace(orig, new)
        if changed:

            print("line {} old: {}".format(lineno, orig_line.strip('\n')))
            print("line {} new: {}\n".format(lineno, line.strip('\n')))
        writefile.write(line)

os.rename(f"{filename}-new", filename)

print(f"Changes saved to {filename}")
