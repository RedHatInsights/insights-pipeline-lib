/**
 * Various utils for insights-client pipelines
 */

/*
* RHSM register
* @param (optional) url = RHSM url
* @param credentialId = Jenkins credential id to authenticate using username and password
* @param (optional) poolId = RHSM pool id
* @param (optional) activationKey = RHSM activation key, usually used for Satellite hosts
* @param (optional) org = Satellite Organization name
*/
def rhsmRegister(
        String url=null,
        String credentialId,
        String poolId=null,
        String activationKey=null,
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
    else if (activationKey){
        sh """
            subscription-manager register --org=${org} --activationkey=${activationKey}
            subscription-manager refresh
        """
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


def rhsmUnregister(){
    def registered = sh ( script: "subscription-manager identity", returnStatus: true)
    if(registered == 0){
        sh '''
            subscription-manager remove --all
            subscription-manager unregister
            subscription-manager clean
        '''
    }
}


def rhsmList(){
    sh "subscription-manager list --available"
}


def rhsmStatus(){
    sh "subscription-manager status"
}


def installClient(String url=null){
    def checkInstalled = sh ( script: 'rpm -qa | grep insights-client', returnStatus: true)

    if(checkInstalled == 0){
        sh """
        yum remove -y insights-client
        rm -rf /etc/insights-client
        """
    }

    if(url){
        sh """
            yum install ${url}
        """
    }
    else {
        sh """
            yum install -y insights-client
        """
    }
}


def collectSystemArtifacts(){
    def sysinfoFileExists = sh ( script: 'test -f \$(hostname).txt', returnStatus: true)
    if(sysinfoFileExists == 0){
        sh """
            rm -rf \$(hostname).txt
        """
    }
    sh '''
    cat /etc/redhat-release >> \$(hostname).txt
    rpm -qa insights-client >> \$(hostname).txt
    echo "ENV_AUTH_TYPE=${ENV_AUTH_TYPE}" >> \$(hostname).txt
    export OS_MAJOR_VERSION=\$(cat /etc/redhat-release | sed 's/.*release //' | sed 's/ .*//' | awk -F. '{ print \$1 }')
    export OS_MINOR_VERSION=\$(cat /etc/redhat-release | sed 's/.*release //' | sed 's/ .*//' | awk -F. '{ print \$2 }')
    export OS_ARCH=\$(uname -m)
    echo "OS_MAJOR_VERSION=\${OS_MAJOR_VERSION}" >> \$(hostname).txt
    echo "OS_MINOR_VERSION=\${OS_MINOR_VERSION}" >> \$(hostname).txt
    echo "OS_ARCH=\${OS_ARCH}" >> \$(hostname).txt
    '''
    archiveArtifacts artifacts: '*.txt'
}


def setupVenvDir(){
    def checkVenvInstalled = sh ( script: 'test -d /iqe_venv', returnStatus: true)
    if(checkVenvInstalled == 0){
        return '/iqe_venv'
    }
    else {
        return 'venv'
    }
}


def setupIqeInsightsClientPlugin(String eggBranch=3.0){
    venvDir = setupVenvDir()
    git credentialsId: 'gitlab', url: 'https://gitlab.cee.redhat.com/insights-qe/iqe-insights-client-plugin.git', branch: "${IQE_BRANCH}"

    if("${venvDir}" == '/iqe_venv'){
        sh """
            echo "/iqe_venv exists"
            source ${venvDir}/bin/activate
            pip install -U pip setuptools setuptools_scm wheel iqe-integration-tests
            iqe plugin install --editable .
            pip install git+https://github.com/RedHatInsights/insights-core.git@${eggBranch}
        """
    }
    else {
        sh """
            git config --global http.sslVerify false
            python3 -m venv venv
            source venv/bin/activate
            pip install devpi-client
            devpi use https://devpi-iqe.cloud.paas.psi.redhat.com/iqe/packages --set-cfg
            pip install -U pip setuptools setuptools_scm wheel
            pip install iqe-integration-tests
            iqe plugin install --editable .
            pip install git+https://github.com/RedHatInsights/insights-core.git@${eggBranch}
        """
    }


    withCredentials([file(credentialsId: 'settings_iqe_insights_client', variable: 'settings')]) {
        sh "cp \$settings iqe_insights_client/conf/settings.local.yaml"
    }
}

def setupIqeAnsible(String iqeAnsibleBranch='master'){
    venvDir = setupVenvDir()
    git credentialsId: 'gitlab', url: 'https://gitlab.cee.redhat.com/insights-qe/iqe-ansible.git', branch: "${iqeAnsibleBranch}"

    if("${venvDir}" != '/iqe_venv'){
        sh """
            git config --global http.sslVerify false
            python3 -m venv venv
            source venv/bin/activate
            pip install --upgrade pip
            pip install -r requirements.txt
        """
    }
    sh """
        echo ${venvDir}
        source ${venvDir}/bin/activate
        pip install -r insights-client/requirements.txt
    """

    withCredentials([file(credentialsId: 'settings_iqe_ansible', variable: 'settings')]) {
        sh "pwd"
        sh "ls -ltr"
        sh "cp \$settings insights-client/vars/settings.local.yaml"
    }
}


def runTests(String pytestParam=null){
        venvDir = setupVenvDir()
        sh """
            source ${venvDir}/bin/activate
            iqe tests plugin insights_client --junitxml=junit.xml --disable-pytest-warnings -rxv ${pytestParam}
        """
}

def runAnsible(String playbookFile, String playbookTags=null){
        echo "Running ansible..."
        venvDir = setupVenvDir()
        if(playbookTags){
            play_command = "ansible-playbook ${playbookFile} --tags test,${playbookTags}"
        }
        else {
            play_command = "ansible-playbook ${playbookFile} --tags test"
        }

        sh """
            cd insights-client/
            cp -pr hosts_localhost hosts
            source ${venvDir}/bin/activate
            export ANSIBLE_LOG_PATH="${WORKSPACE}/ansible_${env.NODE_NAME}.log"
            export JUNIT_OUTPUT_DIR="${WORKSPACE}/"
            ${play_command}
        """

}
