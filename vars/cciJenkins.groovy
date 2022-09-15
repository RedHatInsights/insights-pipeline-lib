/**
 * Functions for jobs on cci-jenkins.
 *
 * WARNING: This module is targeted at:
 * https://gitlab.cee.redhat.com/insights-qe/cci-jenkins-insights-config-declaration/. All other
 * users, caveat emptor.
 *
 * This module provides functions for authoring focused Jenkins jobs which do the following:
 *
 * * Execute tests from a single IQE plugin.
 * * Declaratively define job properties and stages.
 * * Pass functions only the arguments they need.
 *
 * In constrast, execIQETests does the following:
 *
 * * Potentially execute tests from multiple IQE plugins.
 * * Dynamically (i.e. at runtime) define job properties and stages.
 * * Pass functions large maps of arguments.
 *
 * This module doesn't reinvent low-level logic for executing tests. Instead, it makes the
 * lower-level logic that execIQETests executes more accessible.
 */

/**
 * Build arguments for iqeUtils.configIQE().
 *
 * Sample usage:
 *
 *     stage("Configure IQE") {
 *         iqeUtils.configIQE("app name", cciJenkins.configIQEArgs([envName: 'stage_proxy']))
 *     }
 *
 * The defaults herein are derived from iqeUtils.parseOptions().
 */
def configIQEArgs(Map args = [:]) {
    args['extraEnvVars'] = args.get('extraEnvVars', [:])
    args['settingsFileCredentialsId'] = args.get('settingsFileCredentialsId', false)
    args['settingsFromGit'] = args.get('settingsFromGit', false)
    args['vaultEnabled'] = args.get('vaultEnabled', true)
    args['vaultMountPoint'] = args.get('vaultMountPoint', pipelineVars.defaultVaultMountPoint)
    args['vaultRoleIdCredential'] = args.get('vaultRoleIdCredential', pipelineVars.defaultVaultRoleIdCredential)
    args['vaultSecretIdCredential'] = args.get('vaultSecretIdCredential', pipelineVars.defaultVaultSecretIdCredential)
    args['vaultUrl'] = args.get('vaultUrl', pipelineVars.defaultVaultUrl)
    args['vaultVerify'] = args.get('vaultVerify', true)
    return args
}

/**
 * Build arguments for iqeUtils.runIQE().
 *
 * Sample usage:
 *
 *     stage("Run tests") {
 *         iqeUtils.runIQE("config_manager", cciJenkins.runIQEArgs())
 *     }
 *
 * The defaults herein are derived from iqeUtils.parseOptions().
 */
def runIQEArgs(Map args = [:]) {
    args['browserLog'] = args.get('browserLog', false)
    args['extraArgs'] = args.get('extraArgs', '')
    args['filter'] = args.get('filter', '')
    args['ibutsu'] = args.get('ibutsu', true)
    args['ibutsuUrl'] = args.get('ibutsuUrl', pipelineVars.defaultIbutsuUrl)
    args['marker'] = args.get('marker', pipelineVars.defaultMarker)
    args['netlog'] = args.get('netlog', false)
    args['reportportal'] = args.get('reportportal', false)
    args['requirements'] = args.get('requirements', '')
    args['requirementsPriority'] = args.get('requirementsPriority', '')
    args['testImportance'] = args.get('testImportance', '')
    return args
}
