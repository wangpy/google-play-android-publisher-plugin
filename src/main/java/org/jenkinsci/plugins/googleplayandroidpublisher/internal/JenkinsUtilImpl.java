package org.jenkinsci.plugins.googleplayandroidpublisher.internal;

import jenkins.model.Jenkins;
import org.jenkinsci.plugins.googleplayandroidpublisher.Util;

public class JenkinsUtilImpl implements JenkinsUtil {
    @Override
    public String getPluginVersion() {
        final String version = Jenkins.getInstance().getPluginManager().whichPlugin(Util.class).getVersion();
        int index = version.indexOf(' ');
        return (index == -1) ? version : version.substring(0, index);
    }
}
