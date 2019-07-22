package org.jenkinsci.plugins.googleplayandroidpublisher.internal;

import java.io.File;
import java.io.IOException;
import net.dongliu.apk.parser.bean.ApkMeta;

public interface AndroidUtil {
    /**
     * @return The version code of the given APK file.
     */
    default int getApkVersionCode(File apk) throws IOException {
        return getApkMetadata(apk).getVersionCode().intValue();
    }

    /**
     * @return The application ID of the given APK file.
     */
    default String getApkPackageName(File apk) throws IOException {
        return getApkMetadata(apk).getPackageName();
    }

    /**
     * @return The application metadata of the given APK file.
     */
    ApkMeta getApkMetadata(File apk) throws IOException;
}
