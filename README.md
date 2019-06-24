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
 * Parametrized Remote Job Trigger Plugin (custom fork -- https://github.com/bsquizz/parameterized-remote-trigger-plugin -- you'll need to 'mvn compile' and load the `.hpi` via the `Manage Plugins -> Advanced` page). This is for remote triggering on a jenkins master with Open Shift auth enabled (which requires a Bearer token matching an Open Shift user's login token). Note that CSRF checking will need to be disabled for this to work due to https://github.com/openshift/jenkins-openshift-login-plugin/issues/47

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

