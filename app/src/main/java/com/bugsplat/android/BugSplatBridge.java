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

    // Set by init on Android 12+, where attributes and attachments are persisted here
    // instead of being handed to Crashpad.
    private static volatile CrashSession tombstoneSession;

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
        // Mirror the init parameters on the Java side — Crashpad owns this state
        // natively, and postException needs to read it back to build a report.
        BugSplatConfig.init(database, application, version, attributes, attachments);

        // Android 12+ reports native crashes from the system's tombstone on the next launch,
        // so Crashpad is not started there; a crash would otherwise be reported twice.
        if (TombstoneReporter.isSupported()) {
            tombstoneSession = CrashSession.start(activity, database, application, version, attributes,
                    attachments);
            Log.d("BugSplat", "init result: tombstone reporting");
        } else {
            ApplicationInfo applicationInfo = activity.getApplicationInfo();
            Log.d("BugSplat", "init result: " +
                    jniInitBugSplat(applicationInfo.dataDir, applicationInfo.nativeLibraryDir, database,
                            application, version, attributes, attachments));
        }

        // The annotation list does not exist until jniInitBugSplat runs, so a
        // setUser/setEmail/setNotes/setKey call made before init was dropped by
        // the native layer. Replay whatever was set so ordering does not matter.
        BugSplat.applyPromotedAttributes();

        // Check for ANR reports from previous sessions (Android 11+)
        AnrReporter anrReporter = new AnrReporter(activity, database, application, version);
        anrReporter.checkAndReport();

        if (tombstoneSession != null) {
            new TombstoneReporter(activity, tombstoneSession).checkAndReport();
        }
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
            removeAttribute(key);
            return;
        }
        BugSplatConfig.setAttribute(key, value);
        CrashSession session = tombstoneSession;
        if (session != null) {
            session.setAttribute(key, value);
            return;
        }
        jniSetAttribute(key, value);
    }

    public static void removeAttribute(String key) {
        validateAttributeKey(key);
        BugSplatConfig.removeAttribute(key);
        CrashSession session = tombstoneSession;
        if (session != null) {
            session.setAttribute(key, null);
            return;
        }
        jniRemoveAttribute(key);
    }

    public static void addAttachment(String path) {
        AttachmentPath.validate(path);
        CrashSession session = tombstoneSession;
        if (session != null) {
            session.addAttachment(path);
            return;
        }
        jniAddAttachment(path);
    }

    public static void removeAttachment(String path) {
        AttachmentPath.validate(path);
        CrashSession session = tombstoneSession;
        if (session != null) {
            session.removeAttachment(path);
            return;
        }
        jniRemoveAttachment(path);
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

    static native void jniAddAttachment(String path);

    static native void jniRemoveAttachment(String path);
}