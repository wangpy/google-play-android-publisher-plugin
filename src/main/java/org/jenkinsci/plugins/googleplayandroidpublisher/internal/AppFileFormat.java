package org.jenkinsci.plugins.googleplayandroidpublisher.internal;

public enum AppFileFormat {
    APK("APK"),
    BUNDLE("AAB"),
    UNKNOWN("unknown");

    private String name;

    AppFileFormat(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
