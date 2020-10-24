/*
* Subscribe RHEL to RHSM
* @param url
* @param credentialId from Jenkins credential
* @param poolId (optional)
*/
def register(String url, String credentialId, String poolId=null){
    withCredentials([usernamePassword(credentialsId: credentialId, usernameVariable: 'username', passwordVariable: 'password')]) {
        sh """
            subscription-manager register --serverurl=${url} --username=${username} --password=${password}
        """
    }
    if(poolId){
        sh """
            subscription-manager attach --pool=${poolId}
        """
    }
    else {
        sh """
            subscription-manager --auto-attach --force
        """
    }
}

// Unsubscribe RHEL system from RHSM
def unregister(){
    sh '''
        subscription-manager remove --all
        subscription-manager unregister
        subscription-manager clean
    '''
}
