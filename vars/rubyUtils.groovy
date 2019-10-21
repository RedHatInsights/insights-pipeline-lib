def runBundleInstall(parameters = [:]) {
    // Test that bundle install works
    gitUtils.withStatusContext("bundleinstall") {
        cmdStatus = sh script: "bundle install", returnStatus: true
    }
    if (cmdStatus) error "bundle install failed"
}