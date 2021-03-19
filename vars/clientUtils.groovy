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
def getBeta(){
    def beta = sh ( script: 'cat /etc/redhat-release | grep Beta > /dev/null', returnStatus: true)
    if ( beta == 0){
        return true
    }
    else {
        return false
    }
}

def rhsmRegister(
        String url=null,
        String credentialId,
        String poolId=null,
        String activationKey=null,
        String org=null){
    if(poolId){
        withCredentials([usernamePassword(credentialsId: credentialId, usernameVariable: 'username', passwordVariable: 'password')]) {
            echo "Subscribing machine with poolId..."
            sh """
                subscription-manager register --serverurl=${url} --username=${username} --password=${password}
                subscription-manager attach --pool=${poolId}
                subscription-manager refresh
            """
        }
    }
    else if (activationKey){
        echo "Subscribing machine to Satellite ..."
        sh """
            subscription-manager register --org=${org} --activationkey=${activationKey}
            subscription-manager refresh
        """
    }
    else {
        withCredentials([usernamePassword(credentialsId: credentialId, usernameVariable: 'username', passwordVariable: 'password')]) {
            echo "Subscribing machine to Cloud..."
            if(getBeta().toBoolean()){
                echo "We are on beta, do not auto attach subscription..."
                sh """
                    subscription-manager register --serverurl=${url} --username=${username} --password=${password} --force
                    subscription-manager refresh
                """
            }
            else {
                sh """
                    subscription-manager register --serverurl=${url} --username=${username} --password=${password} --auto-attach --force
                    subscription-manager refresh
                """
            }
        }
    }
}


def rhsmUnregister(){
    def registered = sh ( script: "subscription-manager identity", returnStatus: true)
    if(registered == 0){
        echo "Machine is registered, unregistering..."
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


def installRpm(Map parameters = [:]){
    def rpmName = parameters.get("rpmName", null)
    def url = parameters.get("url", null)
    def brewBuildId = parameters.get("brewBuildId",null)
    def brewNVR = parameters.get("brewNVR", null)

    def checkInstalled = sh ( script: "rpm -qa | grep ${rpmName}", returnStatus: true)

    if(checkInstalled == 0){
        sh """
        yum remove -y ${rpmName}
        """
        if ("${rpmName}" == "insights-client"){
            sh """
            rm -rf /etc/insights-client
            """
        }
    }

    if(url){
        sh """
            yum install -y ${url}
        """
    }
    if(brewBuildId){
        sh """
        cd /tmp
        brew download-build --noprogress --arch=x86_64 --debuginfo ${brewBuildId}
        yum localinstall -y /tmp/${brewNVR}.x86_64.rpm
        """
    }
    else {
        if("${rpmName}" == "yggdrasil"){
        sh """
            yum copr enable -y linkdupont/yggdrasil
            yum install -y yggdrasil
        """
        }
        sh """
            yum install -y ${rpmName}
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


def setupIqePlugin(Map parameters = [:]){
// def setupIqePlugin(String plugin,String branch='master'){
    def plugin = parameters.get("plugin")
    def iqeCoreBranch = parameters.get("iqeCoreBranch")
    def iqePluginBranch = parameters.get("iqePluginBranch")

    venvDir = setupVenvDir()
    if(plugin == 'insights-client') {
        git credentialsId: 'gitlab', url: 'https://gitlab.cee.redhat.com/insights-qe/iqe-insights-client-plugin.git', branch: iqePluginBranch
        plugin_dir = 'iqe_insights_client'
        jenkins_credentials = 'settings_iqe_insights_client'
    }
    else if(plugin.contains('rhc')){
        git credentialsId: 'gitlab', url: 'https://gitlab.cee.redhat.com/insights-qe/iqe-rhc-plugin.git', branch: iqePluginBranch
        plugin_dir = 'iqe_rhc'
        jenkins_credentials = 'settings_iqe_rhc'
    }
    else {
        println("Unknown plugin string passed...")
        currentBuild.result = 'FAILURE'
    }

    if("${venvDir}" == '/iqe_venv'){
        sh """
            echo "/iqe_venv exists, reusing it"
            source ${venvDir}/bin/activate
            pip install -U pip setuptools setuptools_scm wheel iqe-integration-tests
            iqe plugin install --editable .
        """
    }
    else {
        sh """
            echo "/iqe_venv does not exist, creating new venv..."
            git config --global http.sslVerify false
            python3 -m venv venv
            source venv/bin/activate
            pip install devpi-client
            devpi use https://devpi-iqe.cloud.paas.psi.redhat.com/iqe/packages --set-cfg
            pip install -U pip setuptools setuptools_scm wheel
            pip install iqe-integration-tests
            iqe plugin install --editable .
        """
    }
    if(plugin == 'insights-client') {
        sh """
            source ${venvDir}/bin/activate
            pip install git+https://github.com/RedHatInsights/insights-core.git@${iqeCoreBranch}
        """
    }


    withCredentials([file(credentialsId: jenkins_credentials, variable: 'settings')]) {
        sh "cp \$settings ${plugin_dir}/conf/settings.local.yaml"
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


def runTests(String plugin=null, String pytestParam=null){
        venvDir = setupVenvDir()
        if (plugin == 'insights-client') {
            plugin_test = 'insights_client'
        }
        else if (plugin == 'rhc') {
            plugin_test = 'rhc'
            pytestParam = "${pytestParam} -m client"
        }
        else if (plugin == 'rhc-worker-playbook') {
            plugin_test = 'rhc'
            pytestParam = "${pytestParam} -m worker_playbook"
            // start python web server with playbook
            sh """
                ls -ltr ./
                cd iqe_rhc/resources/playbooks
                nohup python -m http.server 8000 > /dev/null 2>&1 &
            """
        }
        sh """
            source ${venvDir}/bin/activate
            iqe tests plugin ${plugin_test} --junitxml=junit.xml --disable-pytest-warnings -srxv ${pytestParam}
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
