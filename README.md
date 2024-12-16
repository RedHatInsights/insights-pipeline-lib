# insights-pipeline-lib
Shared library for use in Jenkins pipelines

Required Jenkins plugins:
 * Blue Ocean / all the 'typical' plugins for GitHub multi-branch pipelines
 * GitHub Branch Source Plugin
 * SCM Filter Branch PR Plugin
 * Pipeline GitHub Notify Step Plugin
 * Pipeline: GitHub Plugin
 * SSH Agent Plugin
 * Lockable Resources Plugin
 * Kubernetes Plugin
 * Pipeline Utility Steps Plugin
 * HTTP Request Plugin
 * Parametrized Remote Job Trigger Plugin

For `openShift.withNode`, add a 'jenkins' service account to the namespace and give it "Edit" access. Also add your service
account used by Jenkins in the namespace it is deployed to as an editor (e.g.: "jenkins" svc account from the "jenkins" namespace should also be an editor)

Example:
```
apiVersion: v1
kind: ServiceAccount
metadata:
  name: jenkins

- apiVersion: authorization.openshift.io/v1
  kind: RoleBinding
  metadata:
    generateName: edit-
  roleRef:
    name: edit
  subjects:
  - kind: ServiceAccount
    name: jenkins
    namespace: MY_PROJECT
  - kind: ServiceAccount
    name: jenkins
    namespace: THE_PROJECT_JENKINS_RUNS_IN
  userNames:
  - system:serviceaccount:MY_PROJECT:jenkins
  - system:serviceaccount:THE_PROJECT_JENKINS_RUNS_IN:jenkins
```

## Usage
To be able to use insights-pipeline library you need to add it to your jenkins as shared library. Instruction
is [here](https://www.jenkins.io/doc/book/pipeline/shared-libraries/)

In this repository you can find 2 methods that can be useful for you:
- **execIQETests** - run IQE tests with passed arguments
- **execIQETestsWithNotifier** - run IQE tests with passed arguments and send slack notification at the end of run

### What arguments you can pass and what do they mean
**execIQETests** takes these arguments:

- **appConfigs** - REQUIRED: list of applications and they configs
  - Example: `["my_app": ["options": ["image": "quay.io/foo/bar:my_image"]]`
  - **Note** that you app name is only required here
  - **Note** that list of app options is the same as general options listed below
- **options** -  REQUIRED: list of options defined for pipeline
  - **envName** - REQUIRED: name of environment
    - Example: `stage`
  - **image** - REQUIRED: the container image that the tests will run with in OpenShift
    - Example: `quay.io/foo/bar:my_plugin`
  - **namespace** - OPTIONAL: the namespace that the test pods run in
    - Example: `'dno--jenkins-csb-insights-qe'`
  - **cloud** - OPTIONAL: the name of the 'cloud' under the Jenkins kubernetes plugin settings
    - Example: `'openshift'`
  - **marker** - OPTIONAL: the pytest marker expression (-m) used when running tests
    - Example: `'core'`
  - **filter** - OPTIONAL: the pytest filter expression (-k) used when running tests
    - Example: `'test_navigation_'`
  - **requirements** - OPTIONAL: the iqe --requirements expression used when running tests
    - Example: `'INVENTORY-GROUPS,INVENTORY-SYSTEMS'`
  - **requirementsPriority** - OPTIONAL: the iqe --requirements-priority expression used when running tests
    - Example: `'critical,high'`
  - **testImportance** - OPTIONAL: the iqe --test-importance filter expression used when running tests
    - Example: `'medium,low'`
  - **allocateNode** - OPTIONAL: whether or not to spin up a jenkins pod for running the tests
    - Example: `true`
  - **reportportal** - OPTIONAL: whether or not to report results to reportportal
    - Example: `false`
  - **ibutsu** - OPTIONAL: whether or not to report results to ibutsu
    - Example: `true`
  - **ibutsuUrl** - OPTIONAL: the URL of ibutsu
  - **ui** - OPTIONAL: whether or not to provision a selenium container in the test pod for running UI tests
    - Set to false by default
  - **xdistEnabled** OPTIONAL: enable pytest-xdist plugin for multiprocess parallelism
    - Set to false by default
    - Set it to true if you have tests that run in parallel with pytest marker @parallel
    - **Note** that if you want to run tests in parallel you need to have enough memory provided to pods
  - **parallelWorkerCount** - OPTIONAL: number of pytest-xdist workers to use for parallel tests
    - Example: `2`
  - **extraEnvVars** - OPTIONAL: a Map of additional env vars to set in the .env file before running iqe
    - Example:` ["DYNACONF_MAIN__use_beta": "false"]`
  - **customPackages** - OPTIONAL: list of custom packages to 'pip install' before tests run
    - Example: `["iqe-platform-ui-plugin==0.14.23"]`
  - **extraArgs** - OPTIONAL: extra arguments for plugin tests, i.e. --long-running
  - **browserlog** - OPTIONAL: collect browser console logs
    - Example: `true`
    - It's used for getting more info for debugging
  - **netlog** - OPTIONAL: collect browser network logs
    - Example: `true`
    - It's used for getting more info for debugging
  - **iqeForceDefaultUser** - OPTIONAL: force iqe to use specific user
    - Example: `compliance:compliance_testing_user`
  - **timeout** - OPTIONAL: If pipeline runs more than timeout, jenkins job is killed
    - Example: `60`
- **lockName** - OPTIONAL: name of lock for pipeline that will help to avoid running pipelines at the same time

**Note** that you have 2 types of options: application options and global options. App options overwrite global options
for app specifically. This is made for case when you need to define multiple pipelines run  in parallel.
Options affects every pipeline while appConfigs options affect only specific application pipeline.

**execIQETestsWithNotifier** takes same arguments as execIQETests and some additional:
- **alwaysSendFailureNotification** - OPTIONAL: if true, always send a failure notification
- **alwaysSendSuccessNotification** - OPTIONAL: if true, always send a success notification
- **slackUrl** - OPTIONAL: slack integration URL
- **slackChannel** - REQUIRED: where to report test failures
- **errorSlackChannel** - REQUIRED: where to report unhandled errors when this job unexpectedly fails
- **slackMsgCallback** - OPTIONAL: closure to call that generates detailed slack msg text when tests fail
- **slackSuccessMsgCallback** - OPTIONAL: closure to call that generates detailed slack msg text when tests pass
- **slackTeamDomain** - OPTIONAL: slack team subdomain
- **slackTokenCredentialId** - REQUIRED: slack integration token
- **reqNumBuildsPassBeforeResolved** - OPTIONAL: how many past builds to use before we mark the run as a success

### Example of pipeline definition
```
@Library("github.com/RedHatInsights/insights-pipeline-lib@v5") _

def apps = [
    compliance: [options: [image: "quay.io/cloudservices/iqe-tests:compliance", ui: true]],
]

def options = [
    extraEnvVars: ["DYNACONF_MAIN__use_browser":"chrome"],
    reportportal: true,
    settingsFromGit: true,
    timeout: 60,
    vaultEnabled: true,
    xdistEnabled: false
]

execIQETests(
    appConfigs: apps,
    envs: ["prod"],
    defaultMarker: "core",
    options: options,
)
```
