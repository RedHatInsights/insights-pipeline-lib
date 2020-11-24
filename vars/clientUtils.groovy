/**
 * Various utils for insights-client pipelines
 */

def rhsm_register(
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


def rhsm_unregister(){
    def registered = sh ( script: "subscription-manager identity", returnStatus: true)
    println registered
    if(registered == 0){
        sh '''
            subscription-manager remove --all
            subscription-manager unregister
            subscription-manager clean
        '''
    }
}


def rhsm_list(){
    sh "subscription-manager list --available"
}


def rhsm_status(){
    sh "subscription-manager status"
}


def install_client(String url=null){
    def check_installed = sh ( script: 'rpm -qa | grep insights-client', returnStatus: true)

    if(check_installed == 0){
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


def collect_system_artifacts(){
    def sysinfo_file_exists = sh ( script: 'test -f \$(hostname).txt', returnStatus: true)
    if(sysinfo_file_exists == 0){
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


def setup_venv_dir(){
    def check_venv_installed = sh ( script: 'test -d /iqe_venv', returnStatus: true)
    if(check_venv_installed == 0){
        return '/iqe_venv'
    }
    else {
        return 'venv'
    }
}


def setup_iqe_insights_client_plugin(String egg_branch=3.0){
    venv_dir = setup_venv_dir()
    git credentialsId: 'gitlab', url: 'https://gitlab.cee.redhat.com/insights-qe/iqe-insights-client-plugin.git', branch: "${IQE_BRANCH}"

    if("${venv_dir}" == '/iqe_venv'){
        sh """
            echo "PK: /iqe_venv exists"
            source ${venv_dir}/bin/activate
            devpi use https://devpi-iqe.cloud.paas.psi.redhat.com/iqe/packages --set-cfg
            pip install -U pip setuptools setuptools_scm wheel iqe-integration-tests
            iqe plugin install --editable .
            pip install git+https://github.com/RedHatInsights/insights-core.git@${egg_branch}
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
            pip install git+https://github.com/RedHatInsights/insights-core.git@${egg_branch}
        """
    }


    withCredentials([file(credentialsId: 'settings_iqe_insights_client', variable: 'settings')]) {
        sh "cp \$settings iqe_insights_client/conf/settings.local.yaml"
    }
}


def run_tests(String pytest_param=null){
        venv_dir = setup_venv_dir()
        sh """
            echo 'ENV_FOR_DYNACONF=${ENV_AUTH_TYPE}'
            source ${venv_dir}/bin/activate
            iqe tests plugin insights_client --junitxml=junit.xml --disable-pytest-warnings -rxv ${pytest_param}
        """
}
