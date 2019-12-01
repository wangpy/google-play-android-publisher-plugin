package org.jenkinsci.plugins.googleplayandroidpublisher.internal;

class BundleFileMetadata extends AppFileMetadata {
    BundleFileMetadata(String applicationId, long versionCode, String minSdkVersion) {
        super(applicationId, versionCode, minSdkVersion);
    }
}
