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
    def beta = sh ( script: 'cat /etc/redhat-release | egrep -e "Alpha|Beta" > /dev/null', returnStatus: true)
    if ( beta == 0){
        return true
    }
    else {
        return false
    }
}

def getRhelMajor(){
    def major = sh ( script: 'cat /etc/redhat-release | sed "s/.*release //" | sed "s/ .*//" | awk -F. "{ print \\$1 }" | tr -d "\n"', returnStdout: true)
    return major
}

def rhsmRegister(Map parameters = [:]){
    def url = parameters.get("url", null)
    def credentialId = parameters.get("credentialId", null)
    def poolId = parameters.get("poolId", null)
    def satellite = parameters.get("satellite", null)
    def activationKey = parameters.get("activationKey", null)
    def org = parameters.get("org", null)

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
    else if (satellite){
        echo "Subscribing machine to Satellite ..."
        sh """
            rpm -Uvh http://\$${satellite}/pub/katello-ca-consumer-\$${satellite}-1.0-1.noarch.rpm
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
            ver = getRhelMajor()
            echo "RHEL version is: _${ver}_"
            if(getRhelMajor() == "6"){
                echo "Do not auto attach on RHEL6"
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
    def katelloInstalled = sh ( script: "yum list installed katello-ca-consumer-*", returnStatus: true)
    if(katelloInstalled == 0){
        echo "Katello RPM is installed - uninstallling it..."
        sh '''
            yum remove -y katello-ca-consumer*
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
    def architecture = parameters.get("architecture", "x86_64")

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
        brew download-build --noprogress --arch=${architecture} --debuginfo ${brewBuildId}
        yum localinstall -y /tmp/${brewNVR}.${architecture}.rpm
        """
    }
    else {
        if("${rpmName}" == "yggdrasil"){
        sh """
            yum copr enable -y linkdupont/yggdrasil
            yum copr enable -y jcrafts/rhc-worker-playbook
            yum install -y yggdrasil rhc-worker-playbook
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
    def plugin = parameters.get("plugin")
    def iqeCoreBranch = parameters.get("iqeCoreBranch" , "3.0")
    def iqePluginBranch = parameters.get("iqePluginBranch", "master")
    def satelliteInstance = parameters.get("satelliteInstance" , "satellite_69")
    def jenkinsCredentials = null
    def vaultEnabled = false

    venvDir = setupVenvDir()
    if(plugin == 'insights-client') {
        git credentialsId: 'gitlab', url: 'https://gitlab.cee.redhat.com/insights-qe/iqe-insights-client-plugin.git', branch: iqePluginBranch
        plugin_dir = 'iqe_insights_client'
        jenkinsCredentials = 'settings_iqe_insights_client'
        vaultEnabled = true
    }
    else if(plugin.contains('rhc')){
        git credentialsId: 'gitlab', url: 'https://gitlab.cee.redhat.com/insights-qe/iqe-rhc-client-plugin.git', branch: iqePluginBranch
        vaultEnabled = true
    }
    else if(plugin.contains('iqe-satellite-plugin')){
        git credentialsId: 'gitlab', url: 'https://gitlab.cee.redhat.com/insights-qe/iqe-satellite-plugin.git', branch: iqePluginBranch
        plugin_dir = 'iqe-satellite-plugin'
        plugin_dir = 'iqe_insights_satellite'
        jenkinsCredentials = 'settings_iqe_satellite'
    }
    else {
        println("Unknown plugin string passed...")
        currentBuild.result = 'FAILURE'
    }

    if("${venvDir}" == '/iqe_venv'){
        sh """
            echo "/iqe_venv exists, reusing it"
            source ${venvDir}/bin/activate
            pip install -U pip setuptools wheel
            pip install setuptools_scm iqe-core
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
            pip install -U pip setuptools wheel
            pip install setuptools_scm iqe-core
            iqe plugin install --editable .
        """
    }
    if(plugin == 'insights-client') {
        sh """
            source ${venvDir}/bin/activate
            pip install git+https://github.com/RedHatInsights/insights-core.git@${iqeCoreBranch}
        """
    }
    else if(plugin == 'iqe-satellite-plugin') {
        sh """
            source ${venvDir}/bin/activate
            yum -y install docker
            docker pull quay.io/cloudservices/selenium-standalone-chrome:3.141.59-xenon
            docker image tag quay.io/cloudservices/selenium-standalone-chrome:3.141.59-xenon selenium/standalone-chrome:latest
            python -m pip install docker-py
            python -m pip install -e .[${satelliteInstance}]
            python -m pip uninstall -y python-box
            python -m pip install python-box==3.4.6
        """
            // systemctl enable docker.service
            // systemctl start docker.service
    }
    else if(plugin.contains('rhc')) {
        sh """
            source ${venvDir}/bin/activate
            pip install --editable .[client]
        """
    }

    if (jenkinsCredentials) {
        withCredentials([file(credentialsId: jenkinsCredentials, variable: 'settings')]) {
            sh "cp \$settings ${plugin_dir}/conf/settings.local.yaml"
        }
    }

    // Remove workspace .env file
    sh "rm -f \"${env.WORKSPACE}/.env\""

    if (vaultEnabled) {
        vaultParameters = setupVaultParameters()
        iqeUtils.writeVaultEnvVars(vaultParameters)
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


def runTests(Map parameters = [:]){
    def plugin = parameters.get("plugin")
    def env = parameters.get("env", null)
    def pytestParam = parameters.get("pytestParam", null)
    def satelliteInstance = parameters.get("satelliteInstance", null)
    def iqeVmRhel = parameters.get("iqeVmRhel", null)
    if (iqeVmRhel){
        replaced_rhel_string = iqeVmRhel.replaceAll( /rhel/, 'rhel_' )
    }
    else {
        replaced_rhel_string = null
    }
    def ibutsu = parameters.get("ibutsu", true)
    def reportportal = parameters.get('reportportal', false)

        venvDir = setupVenvDir()
        if (plugin == 'insights-client') {
            plugin_test = 'insights_client'
        }
        else if (plugin == 'rhc') {
            plugin_test = 'rhc_client'
            pytestParam = "${pytestParam} -k test_client"
        }
        else if (plugin == 'rhc-worker-playbook') {
            plugin_test = 'rhc_client'
            pytestParam = "${pytestParam} -m worker_playbook"
            // start python web server with playbook
            sh """
                ls -ltr ./
                cd iqe_rhc_client/resources/playbooks
                nohup python -m http.server 8000 > /dev/null 2>&1 &
            """
        }
        else if (plugin == 'iqe-satellite-plugin') {
            plugin_test = 'insights_satellite'
            sh """
                podman stop iqe_selenium_standalone || true
                podman rm iqe_selenium_standalone || true
                docker run -it -d --shm-size=2g -p 4444:4444 -p 5999:5999 --name iqe_selenium_standalone quay.io/redhatqe/selenium-standalone
                sleep 8
            """
        }
        if (ibutsu) {
            pytestParam = "${pytestParam} --ibutsu https://ibutsu-api.apps.ocp4.prod.psi.redhat.com --ibutsu-source stg-jenkins"
            if (env) {
                pytestParam = "${pytestParam} --ibutsu-data env=${env}"
            }
        }

        if (reportportal) {
            pytestParam = "${pytestParam} --reportportal"
        }

        // iqe tests plugin ${plugin_test} --junitxml=junit.xml --disable-pytest-warnings -srxv ${pytestParam}
        sh """
            set +x && export \$(cat "${WORKSPACE}/.env" | xargs) && set -x
            export SATELLITE_INSTANCE=${satelliteInstance}
            export IQE_VM_RHEL=${replaced_rhel_string}
            source ${venvDir}/bin/activate
            iqe tests plugin ${plugin_test} --junitxml=junit.xml --disable-pytest-warnings -srxv ${pytestParam} -vvv --capture=sys
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

def copySshKey(Map parameters = [:]){
    def sshKey = parameters.get("sshKey", 'ssh_credentials_remediations_fifi')
    def sshKeyName = parameters.get("sshKeyName", 'insights-qa.pem')
    withCredentials([file(credentialsId: sshKey, variable: 'settings')]) {
        sh "cp \$settings ~/.ssh/${sshKeyName}"
    }
}

def setupVaultParameters() {
    def params = [:]
    params['vaultEnabled'] = true
    params['vaultUrl'] = pipelineVars.defaultVaultUrl
    params['vaultMountPoint'] = pipelineVars.defaultVaultMountPoint
    params['vaultTokenCredential'] = null
    params['vaultRoleIdCredential'] = pipelineVars.defaultVaultRoleIdCredential
    params['vaultSecretIdCredential'] = pipelineVars.defaultVaultSecretIdCredential
    params['vaultVerify'] = true

    return params
}
