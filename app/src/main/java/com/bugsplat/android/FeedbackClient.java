package com.bugsplat.android;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class FeedbackClient {
    private static final String TAG = "BugSplat";
    private static final String CRASH_TYPE = "UserFeedback";
    private static final int CRASH_TYPE_ID = 36;

    private final String database;
    private final String application;
    private final String version;
    private final ReportUploader uploader;

    FeedbackClient(String database, String application, String version) {
        this(database, application, version, new ReportUploader(database, application, version));
    }

    /** Package-private constructor for testing with a custom uploader. */
    FeedbackClient(String database, String application, String version, ReportUploader uploader) {
        this.database = database;
        this.application = application;
        this.version = version;
        this.uploader = uploader;
    }

    boolean postFeedback(String title, String description, String user, String email, String appKey) {
        return postFeedback(title, description, user, email, appKey, null);
    }

    boolean postFeedback(String title, String description, String user, String email, String appKey, List<File> attachments) {
        try {
            // Build a simple text report with the feedback fields.
            StringBuilder report = new StringBuilder();
            report.append("Title: ").append(title != null ? title : "").append("\n");
            if (description != null && !description.isEmpty()) {
                report.append("Description: ").append(description).append("\n");
            }
            if (user != null && !user.isEmpty()) {
                report.append("User: ").append(user).append("\n");
            }
            if (email != null && !email.isEmpty()) {
                report.append("Email: ").append(email).append("\n");
            }
            if (appKey != null && !appKey.isEmpty()) {
                report.append("AppKey: ").append(appKey).append("\n");
            }

            // Pack the report plus any attachment bytes into a single zip
            // (LinkedHashMap preserves insertion order so feedback.txt is first).
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("feedback.txt", report.toString().getBytes(StandardCharsets.UTF_8));

            if (attachments != null) {
                for (File file : attachments) {
                    if (file == null || !file.exists() || !file.isFile()) {
                        Log.w(TAG, "Skipping invalid attachment: " + file);
                        continue;
                    }
                    entries.put(uniqueEntryName(entries, file.getName()), readFileBytes(file));
                }
            }

            boolean success = uploader.upload(entries, CRASH_TYPE, CRASH_TYPE_ID);
            if (success) {
                Log.i(TAG, "Feedback posted successfully");
            } else {
                Log.e(TAG, "Failed to post feedback");
            }
            return success;

        } catch (Exception e) {
            Log.e(TAG, "Failed to post feedback", e);
            return false;
        }
    }

    private static byte[] readFileBytes(File file) throws IOException {
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = fis.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
        }
        return data;
    }

    /** Disambiguate entries if two attachments share a filename. */
    private static String uniqueEntryName(Map<String, byte[]> entries, String name) {
        if (!entries.containsKey(name)) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int n = 1;
        String candidate;
        do {
            candidate = base + "_" + n + ext;
            n++;
        } while (entries.containsKey(candidate));
        return candidate;
    }
}
