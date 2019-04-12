package com.redhat;

import java.io.IOException;
import java.net.URLConnection;

@Grab('org.jenkins-ci.plugins:Parameterized-Remote-Trigger:3.0.7@jar')
import org.jenkinsci.plugins.ParameterizedRemoteTrigger.BuildContext;
import org.jenkinsci.plugins.ParameterizedRemoteTrigger.auth2.TokenAuth;


class BearerTokenAuth extends TokenAuth {
    // Override the plugin's TokenAuth to send a bearer token instead of a basic auth header

    // This is needed to auth with Jenkins running on Open Shift -- since the API in that case requires an oc bearer token
    @Override
    public void setAuthorizationHeader(URLConnection connection, BuildContext context) throws IOException {
        connection.setRequestProperty("Authorization", "Bearer: " + this.getApiToken())
    }
}
