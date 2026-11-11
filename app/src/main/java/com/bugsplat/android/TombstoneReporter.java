package com.bugsplat.android;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Reports native crashes on Android 12+ (API 31+) from the tombstone the system wrote for
 * the crashed process. Crashpad is not started on these versions, so each crash is
 * reported once.
 *
 * On each SDK initialization, this class checks the historical exit reasons for
 * {@code REASON_CRASH_NATIVE} records it has not reported yet. For each one, it uploads the
 * raw tombstone protobuf from {@code getTraceInputStream()} with the metadata persisted by
 * the crashed process (see {@link CrashSession}) and that session's attachments, read now.
 * Mirrors {@link AnrReporter}.
 */
class TombstoneReporter {
    private static final String TAG = "BugSplat-Tombstone";
    private static final String PREFS_NAME = "bugsplat_tombstone";
    private static final String PREF_LAST_REPORTED_TIMESTAMP = "last_reported_tombstone_timestamp";
    private static final String CRASH_TYPE = "Android.Tombstone";
    private static final int CRASH_TYPE_ID = 38;
    static final String TOMBSTONE_ENTRY = "tombstone.pb";

    // The exit history does not change for this process, so a second init does not scan it
    // again; two scans could read the same timestamp and upload a crash twice.
    private static final AtomicBoolean scanned = new AtomicBoolean();

    private final Context context;
    private final CrashSession current;
    private final ExecutorService executor;

    TombstoneReporter(Context context, CrashSession current) {
        this.context = context.getApplicationContext();
        this.current = current;
        this.executor = Executors.newSingleThreadExecutor();
    }

    /** getTraceInputStream() returns the tombstone for native crashes from Android 12. */
    static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /** True for a native crash newer than the last one reported. */
    static boolean isUnreported(int reason, long timestamp, long lastReportedTimestamp) {
        return reason == ApplicationExitInfo.REASON_CRASH_NATIVE && timestamp > lastReportedTimestamp;
    }

    void checkAndReport() {
        if (!isSupported() || !scanned.compareAndSet(false, true)) {
            return;
        }

        // First launch with tombstone reporting: native crashes of earlier processes were
        // reported by Crashpad, or happened before BugSplat was added, so they are not
        // reported. The start is this process's, and it is written before init returns, so
        // a crash right after init is still newer than it.
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.contains(PREF_LAST_REPORTED_TIMESTAMP)) {
            long processStart = System.currentTimeMillis()
                    - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime());
            prefs.edit().putLong(PREF_LAST_REPORTED_TIMESTAMP, processStart).commit();
        }

        executor.execute(() -> {
            try {
                checkAndReportInternal();
            } catch (Exception e) {
                Log.e(TAG, "Failed to check/report tombstones", e);
            }
        });
        executor.shutdown();
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void checkAndReportInternal() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastReportedTimestamp = prefs.getLong(PREF_LAST_REPORTED_TIMESTAMP, 0);

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            Log.w(TAG, "ActivityManager unavailable");
            return;
        }

        List<ApplicationExitInfo> exitInfos = am.getHistoricalProcessExitReasons(
                context.getPackageName(), 0, 0);
        if (exitInfos == null) {
            exitInfos = Collections.emptyList();
        }

        // Oldest first, stopping on the first failed upload, as in AnrReporter.
        List<ApplicationExitInfo> orderedOldestFirst = new ArrayList<>(exitInfos);
        Collections.reverse(orderedOldestFirst);

        File sessions = CrashSession.directory(context);
        long advanceTo = lastReportedTimestamp;

        for (ApplicationExitInfo exitInfo : orderedOldestFirst) {
            long timestamp = exitInfo.getTimestamp();
            if (!isUnreported(exitInfo.getReason(), timestamp, lastReportedTimestamp)) {
                continue;
            }

            byte[] tombstone = readTraceStream(exitInfo);
            if (tombstone == null || tombstone.length == 0) {
                Log.w(TAG, "No tombstone for native crash at " + timestamp + ", skipping");
                if (!markReported(prefs, timestamp)) {
                    break;
                }
                advanceTo = Math.max(advanceTo, timestamp);
                continue;
            }

            CrashSession session = CrashSession.load(sessions, exitInfo.getPid(), timestamp);
            if (session == null) {
                Log.w(TAG, "No session for pid " + exitInfo.getPid() + ", reporting with current values");
                session = current;
            }

            Log.i(TAG, "Reporting native crash at " + timestamp + " (pid=" + exitInfo.getPid() + ")");

            UploadResult result = UploadResult.failure();
            try {
                // Reserved attributes (BugSplatUser and friends) are promoted to report
                // fields by the backend, as they are for Crashpad annotations.
                CommitOptions options = new CommitOptions()
                        .crashType(CRASH_TYPE)
                        .crashTypeId(CRASH_TYPE_ID)
                        .attributes(session.attributes());
                result = new ReportUploader(session.database, session.application, session.version)
                        .uploadWithResult(zip(tombstone, session.attachments()), options);
            } catch (IOException e) {
                Log.e(TAG, "Failed to upload tombstone", e);
            }

            if (!result.success) {
                break; // retried next launch
            }
            Log.i(TAG, "Tombstone uploaded, crashId=" + result.crashId);
            if (!markReported(prefs, timestamp)) {
                break; // reported again next launch, so its session is kept
            }
            advanceTo = Math.max(advanceTo, timestamp);
        }

        // Keep the sessions of running processes and of crashes not reported yet. Every other
        // session belongs to a process that has exited and will never be reported.
        Set<Integer> keep = new HashSet<>();
        keep.add(Process.myPid());
        List<ActivityManager.RunningAppProcessInfo> running = am.getRunningAppProcesses();
        if (running != null) {
            for (ActivityManager.RunningAppProcessInfo process : running) {
                keep.add(process.pid);
            }
        }
        for (ApplicationExitInfo exitInfo : exitInfos) {
            if (isUnreported(exitInfo.getReason(), exitInfo.getTimestamp(), advanceTo)) {
                keep.add(exitInfo.getPid());
            }
        }
        deleteSessionsExcept(sessions, keep);
    }

    /**
     * Saved after each crash, not at the end of the pass, so a crash already uploaded is not
     * uploaded again if the process dies during a later upload. Crashes are visited oldest
     * first, so the timestamp only grows. False when it could not be saved: the pass stops,
     * since every later upload would be repeated on the next launch.
     */
    private static boolean markReported(SharedPreferences prefs, long timestamp) {
        if (prefs.edit().putLong(PREF_LAST_REPORTED_TIMESTAMP, timestamp).commit()) {
            return true;
        }
        Log.e(TAG, "Could not save the last reported timestamp; stopping until next launch");
        return false;
    }

    /** Deletes session files, and leftover temp files, of every PID not in {@code keep}. */
    static void deleteSessionsExcept(File directory, Set<Integer> keep) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            int dot = name.indexOf('.');
            int pid;
            try {
                pid = Integer.parseInt(dot < 0 ? name : name.substring(0, dot));
            } catch (NumberFormatException e) {
                continue;
            }
            if (!keep.contains(pid)) {
                file.delete();
            }
        }
    }

    /**
     * The tombstone followed by the attachments. Attachments are read now, on the launch
     * after the crash; missing files are skipped, as are files whose name is already taken.
     */
    static byte[] zip(byte[] tombstone, List<String> attachments) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Set<String> names = new HashSet<>();
        names.add(TOMBSTONE_ENTRY);
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(TOMBSTONE_ENTRY));
            zos.write(tombstone);
            zos.closeEntry();

            for (String path : attachments) {
                File file = new File(path);
                if (!file.isFile() || names.contains(file.getName())) {
                    Log.w(TAG, "Skipping attachment " + path);
                    continue;
                }

                byte[] data;
                try {
                    data = Files.readAllBytes(file.toPath());
                } catch (IOException e) {
                    Log.w(TAG, "Could not read attachment " + path, e);
                    continue;
                }

                names.add(file.getName());
                zos.putNextEntry(new ZipEntry(file.getName()));
                zos.write(data);
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private byte[] readTraceStream(ApplicationExitInfo exitInfo) {
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
            return baos.toByteArray();
        } catch (IOException e) {
            Log.e(TAG, "Failed to read tombstone", e);
            return null;
        }
    }
}
