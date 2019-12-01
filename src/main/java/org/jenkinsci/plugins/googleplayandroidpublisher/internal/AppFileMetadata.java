package org.jenkinsci.plugins.googleplayandroidpublisher.internal;

import java.io.Serializable;

public abstract class AppFileMetadata implements Serializable {

    private final String applicationId;
    private final long versionCode;
    private final String minSdkVersion;

    AppFileMetadata(String applicationId, long versionCode, String minSdkVersion) {
        this.applicationId = applicationId;
        this.versionCode = versionCode;
        this.minSdkVersion = minSdkVersion;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public long getVersionCode() {
        return versionCode;
    }

    public String getMinSdkVersion() {
        return minSdkVersion;
    }

}
