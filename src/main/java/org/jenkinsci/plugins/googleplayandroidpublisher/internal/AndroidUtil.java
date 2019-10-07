package org.jenkinsci.plugins.googleplayandroidpublisher.internal;

import java.io.File;
import java.io.IOException;

import com.github.orrc.android.bundle.AndroidBundleMetadataParser;
import com.github.orrc.android.bundle.BundleParser;
import net.dongliu.apk.parser.bean.ApkMeta;
import org.jenkinsci.plugins.googleplayandroidpublisher.AppFileMetadata;

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

    default AppFileMetadata getAppFileMetadata(File file) throws IOException {
        if (file.getName().endsWith(".aab")) {
            BundleParser parser = new AndroidBundleMetadataParser(file);
            return new AppFileMetadata(parser.getApplicationId(), parser.getVersionCode(), parser.getMinSdkVersion());
        }

        ApkMeta apkMeta = getApkMetadata(file);
        return new AppFileMetadata(apkMeta.getPackageName(), apkMeta.getVersionCode(), apkMeta.getMinSdkVersion());
    }

    /**
     * @return The application metadata of the given APK file.
     */
    ApkMeta getApkMetadata(File apk) throws IOException;
}
