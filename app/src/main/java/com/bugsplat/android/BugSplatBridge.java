package com.bugsplat.android;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.util.Log;

public class BugSplatBridge {
    // Static initializer to load native library (equivalent to Kotlin's init block)
    static {
        System.loadLibrary("bugsplat");
    }

    // Private constructor to prevent instantiation (mimicking Kotlin object)
    private BugSplatBridge() {
    }

    public static void initBugSplat(Activity activity, String database, String application, String version) {
        initBugSplat(activity, database, application, version, null);
    }

    public static void initBugSplat(Activity activity, String database, String application, String version,
            String[] attachments) {
        ApplicationInfo applicationInfo = activity.getApplicationInfo();
        Log.d("BugSplat", "init result: " +
                jniInitBugSplat(applicationInfo.dataDir, applicationInfo.nativeLibraryDir, database, application,
                        version, attachments));
    }

    public static void crash() {
        jniCrash();
    }

    static native boolean jniInitBugSplat(String dataDir, String libDir, String database, String application,
            String version, String[] attachments);

    static native void jniCrash();
}