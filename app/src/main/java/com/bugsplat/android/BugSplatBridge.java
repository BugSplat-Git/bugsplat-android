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

    // Singleton instance
    private static final BugSplatBridge INSTANCE = new BugSplatBridge();

    // Public method to get the singleton instance
    public static BugSplatBridge getInstance() {
        return INSTANCE;
    }

    public void initBugSplat(Activity activity, String database, String application, String version) {
        ApplicationInfo applicationInfo = activity.getApplicationInfo();
        Log.d("BugSplat", "init result: " +
                jniInitBugSplat(
                    applicationInfo.dataDir,
                    applicationInfo.nativeLibraryDir,
                    database,
                    application,
                    version
                )
        );
    }

    public void crash() {
        jniCrash();
    }

    // Native method declarations
    public native boolean jniInitBugSplat(String dataDir, String libDir, String database, String application, String version);
    public native void jniCrash();
}