package com.redhat.insights;

import java.io.IOException;
import java.net.URLConnection;

@GrabResolver(name='jenkins', root='http://repo.jenkins-ci.org/public/')
@Grab(group='org.jenkins-ci.plugins', module='Parameterized-Remote-Trigger', version='3.0.7')
import org.jenkinsci.Symbol;

import org.jenkinsci.plugins.ParameterizedRemoteTrigger.BuildContext;
import org.jenkinsci.plugins.ParameterizedRemoteTrigger.utils.Base64Utils;
import org.jenkinsci.plugins.ParameterizedRemoteTrigger.auth2.Auth2;
import org.jenkinsci.plugins.ParameterizedRemoteTrigger.auth2.Auth2.Auth2Descriptor;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import hudson.Extension;
import hudson.model.Item;

public class BearerTokenAuth extends Auth2 {

    private static final long serialVersionUID = 0513801413382879272L;

    @Extension
    public static final Auth2Descriptor DESCRIPTOR = new TokenAuthDescriptor();

    private String token;

    @DataBoundConstructor
    public TokenAuth() {
        this.token = null;
    }

    @DataBoundSetter
    public void setToken(String token) {
        this.token = token;
    }

    public String getToken() {
        return this.token;
    }

    @Override
    public void setAuthorizationHeader(URLConnection connection, BuildContext context) throws IOException {
        connection.setRequestProperty("Authorization", "Bearer: " + getToken())
    }

    @Override
    public String toString() {
        return "'" + getDescriptor().getDisplayName() + "'";
    }

    @Override
    public String toString(Item item) {
        return toString();
    }

    @Override
    public Auth2Descriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Symbol("BearerTokenAuth")
    public static class BearerTokenAuthDescriptor extends Auth2Descriptor {
        @Override
        public String getDisplayName() {
            return "Bearer Token Authentication";
        }
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((token == null) ? 0 : token.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (!this.getClass().isInstance(obj))
            return false;
        BearerTokenAuth other = (BearerTokenAuth) obj;
        if (token == null) {
            if (other.token != null)
                return false;
        } else if (!token.equals(other.token)) {
            return false;
        }
        return true;
    }

}