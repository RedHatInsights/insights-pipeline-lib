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

For `openShift.withNode`, if you want to deploy to a non-default namespace, make sure you add the proper service
account to the namespace. See https://github.com/jenkinsci/kubernetes-plugin#integration-tests-in-a-different-cluster
