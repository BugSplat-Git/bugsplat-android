package com.bugsplat.android;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Detects and reports ANR (Application Not Responding) events using the
 * ApplicationExitInfo API available on Android 11+ (API 30+).
 *
 * On each SDK initialization, this class checks for historical ANR exit reasons
 * from previous process instances. For each new (unreported) ANR, it reads the
 * system-provided thread dump and uploads it to BugSplat via the 3-part S3 upload.
 *
 * The thread dump is plain text in the same format as /data/anr/traces.txt and
 * includes all threads with full Java + native stack traces, lock info, and
 * BuildIds for native frames. Native frame addresses are relative to the library
 * load address and can be symbolicated against .sym files by matching FUNC/line
 * records.
 */
class AnrReporter {
    private static final String TAG = "BugSplat-ANR";
    private static final String PREFS_NAME = "bugsplat_anr";
    private static final String PREF_LAST_REPORTED_TIMESTAMP = "last_reported_anr_timestamp";
    private static final String CRASH_TYPE = "Android ANR";
    private static final int CRASH_TYPE_ID = 37;

    private final Context context;
    private final String database;
    private final String application;
    private final String version;
    private final ExecutorService executor;

    AnrReporter(Context context, String database, String application, String version) {
        this.context = context.getApplicationContext();
        this.database = database;
        this.application = application;
        this.version = version;
        this.executor = Executors.newSingleThreadExecutor();
    }

    /**
     * Check for unreported ANRs and upload them in the background.
     * Safe to call on any API level — silently no-ops below Android 11.
     */
    void checkAndReport() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.d(TAG, "ANR reporting requires Android 11+ (API 30+), skipping");
            return;
        }

        executor.execute(() -> {
            try {
                checkAndReportInternal();
            } catch (Exception e) {
                Log.e(TAG, "Failed to check/report ANRs", e);
            }
        });
    }

    private void checkAndReportInternal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            Log.w(TAG, "ActivityManager unavailable");
            return;
        }

        List<ApplicationExitInfo> exitInfos = am.getHistoricalProcessExitReasons(
                context.getPackageName(), 0, 0);

        if (exitInfos == null || exitInfos.isEmpty()) {
            Log.d(TAG, "No historical exit reasons found");
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastReportedTimestamp = prefs.getLong(PREF_LAST_REPORTED_TIMESTAMP, 0);
        long newestAnrTimestamp = lastReportedTimestamp;

        ReportUploader uploader = new ReportUploader(database, application, version);

        for (ApplicationExitInfo exitInfo : exitInfos) {
            if (exitInfo.getReason() != ApplicationExitInfo.REASON_ANR) {
                continue;
            }

            long timestamp = exitInfo.getTimestamp();
            if (timestamp <= lastReportedTimestamp) {
                break;
            }

            newestAnrTimestamp = Math.max(newestAnrTimestamp, timestamp);

            String threadDump = readTraceStream(exitInfo);
            if (threadDump == null || threadDump.isEmpty()) {
                Log.w(TAG, "Empty trace stream for ANR at " + timestamp);
                continue;
            }

            boolean foreground = exitInfo.getImportance()
                    == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;

            Log.i(TAG, "Reporting ANR at " + timestamp
                    + " (pid=" + exitInfo.getPid()
                    + ", foreground=" + foreground
                    + ", description=" + exitInfo.getDescription() + ")");

            try {
                uploader.upload(
                        threadDump.getBytes("UTF-8"),
                        "anr_trace.txt",
                        CRASH_TYPE,
                        CRASH_TYPE_ID
                );
            } catch (IOException e) {
                Log.e(TAG, "Failed to upload ANR report", e);
            }
        }

        if (newestAnrTimestamp > lastReportedTimestamp) {
            prefs.edit()
                    .putLong(PREF_LAST_REPORTED_TIMESTAMP, newestAnrTimestamp)
                    .apply();
        }
    }

    private String readTraceStream(ApplicationExitInfo exitInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null;
        }

        try (InputStream is = exitInfo.getTraceInputStream()) {
            if (is == null) {
                return null;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            return baos.toString("UTF-8");
        } catch (IOException e) {
            Log.e(TAG, "Failed to read ANR trace stream", e);
            return null;
        }
    }
}
