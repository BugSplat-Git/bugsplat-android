package com.bugsplat.android;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.util.Log;
import java.util.Map;

public class BugSplatBridge {
    // Static initializer to load native library (equivalent to Kotlin's init block)
    static {
        System.loadLibrary("bugsplat");
    }

    // Private constructor to prevent instantiation (mimicking Kotlin object)
    private BugSplatBridge() {
    }

    public static void initBugSplat(Activity activity, String database, String application, String version) {
        initBugSplat(activity, database, application, version, null, null);
    }

    public static void initBugSplat(Activity activity, String database, String application, String version,
            String[] attachments) {
        initBugSplat(activity, database, application, version, null, attachments);
    }

    public static void initBugSplat(Activity activity, String database, String application, String version,
            Map<String, String> attributes) {
        initBugSplat(activity, database, application, version, attributes, null);
    }

    public static void initBugSplat(Activity activity, String database, String application, String version,
            Map<String, String> attributes, String[] attachments) {
        ApplicationInfo applicationInfo = activity.getApplicationInfo();
        Log.d("BugSplat", "init result: " +
                jniInitBugSplat(applicationInfo.dataDir, applicationInfo.nativeLibraryDir, database, application,
                        version, attributes, attachments));

        // Check for ANR reports from previous sessions (Android 11+)
        AnrReporter anrReporter = new AnrReporter(activity, database, application, version);
        anrReporter.checkAndReport();
    }

    public static void crash() {
        jniCrash();
    }

    /**
     * Hang the calling thread in a native infinite loop. Intended for testing
     * ANR detection and symbolication of native frames.
     */
    public static void hang() {
        jniHang();
    }

    public static void setAttribute(String key, String value) {
        validateAttributeKey(key);
        if (value == null) {
            jniRemoveAttribute(key);
            return;
        }
        jniSetAttribute(key, value);
    }

    public static void removeAttribute(String key) {
        validateAttributeKey(key);
        jniRemoveAttribute(key);
    }

    private static void validateAttributeKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Attribute key must not be null or blank");
        }
    }

    static native boolean jniInitBugSplat(String dataDir, String libDir, String database, String application,
            String version, Map<String, String> attributes, String[] attachments);

    static native void jniCrash();

    static native void jniHang();

    static native void jniSetAttribute(String key, String value);

    static native void jniRemoveAttribute(String key);
}