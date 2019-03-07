/* 
 *  Waits until source code in a pod will have a certain commit an then waits until it will be
 *  fully deployed. It assumes that pods and replication controllers have one common label and its
 *  value. E.g. "app: compliance-backend".
 */

def call(parameters = [:]) {
    def cluster = parameters["cluster"]
    def credentials = parameters["credentials"]
    def project = parameters["project"]
    def minutes = parameters["minutes"]
    def label = parameters["label"]
    def value = parameters["value"]
    def gitCommit = parameters["gitCommit"]
    openshift.withCluster(cluster) {
        openshift.withCredentials(credentials) {
            openshift.withProject(project) {
                timeout(minutes) {
                    finished = false
                    waitUntil {
                        try {
                            pod = openshift.selector("pod", [(label): value])
                            def podGitCommit = openshift.rsh("${pod.name()} git rev-parse HEAD").out.trim()
                            // Once a new code appeared in a pod we need to wait until it will be fully
                            // deployed.
                            if (podGitCommit == gitCommit) {
                                echo "Pod (${pod.name()}) with commit hash $podGitCommit has been spawned"
                                waitUntil {
                                    // Get the latest replication controller and check its status
                                    def lastRc = openshift.selector("rc", [(label): value]).objects()[-1]
                                    finished = lastRc.status.replicas == lastRc.status.readyReplicas
                                    if (finished) {
                                        echo "Pod (${pod.name()}) has been deployed"
                                    }
                                    return finished
                                }
                            }
                            return finished
                        } catch(err) {
                            echo "Error occured: ${err.getMessage()}"
                            return false
                        }
                    }
                }
            }
        }
    }
}
