/*
* RHSM register
* @param (optional) url = RHSM url
* @param credentialId = Jenkins credential id to authenticate using username and password
* @param (optional) poolId = RHSM pool id
* @param (optional) activationKey = RHSM activation key, usually used for Satellite hosts
* @param (optional) org = Satellite Organization name
*/
def register(
        String url=null, 
        String credentialId, 
        String poolId=null, 
        String activiationKey=null, 
        String org=null){
    if(poolId){
        withCredentials([usernamePassword(credentialsId: credentialId, usernameVariable: 'username', passwordVariable: 'password')]) {
            sh """
                subscription-manager register --serverurl=${url} --username=${username} --password=${password}
                subscription-manager attach --pool=${poolId}
                subscription-manager refresh
            """
        }
    }
    else if (activiationKey){
        withCredentials([usernamePassword(credentialsId: credentialId, usernameVariable: 'username', passwordVariable: 'password')]) {
            sh """
                subscription-manager register --org=${org} --activationkey=${activiationKey}
                subscription-manager refresh
            """
        }
    }
    else {
        withCredentials([usernamePassword(credentialsId: credentialId, usernameVariable: 'username', passwordVariable: 'password')]) {
            sh """
                subscription-manager register --serverurl=${url} --username=${username} --password=${password} --auto-attach --force
                subscription-manager refresh
            """
        }
    }
}

def unregister(){
    sh '''
        subscription-manager remove --all
        subscription-manager unregister
        subscription-manager clean
    '''
}

def list(){
    sh "subscription-manager list --available"
}

def status(){
    sh "subscription-manager status"
}