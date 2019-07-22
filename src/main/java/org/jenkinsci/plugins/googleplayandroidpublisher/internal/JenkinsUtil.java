package org.jenkinsci.plugins.googleplayandroidpublisher.internal;

public interface JenkinsUtil {
    /**
     * @return The version of this Jenkins plugin, e.g. "1.0" or "1.1-SNAPSHOT" (for dev releases).
     */
    String getPluginVersion();
}
