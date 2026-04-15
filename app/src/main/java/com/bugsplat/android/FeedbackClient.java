package com.bugsplat.android;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
            // Build a simple text report with the feedback fields
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

            // Add attachment info
            if (attachments != null) {
                for (File file : attachments) {
                    if (file != null && file.exists() && file.isFile()) {
                        report.append("Attachment: ").append(file.getName())
                              .append(" (").append(file.length()).append(" bytes)\n");
                    }
                }
            }

            boolean success = uploader.upload(
                    report.toString().getBytes(StandardCharsets.UTF_8),
                    "feedback.txt",
                    CRASH_TYPE,
                    CRASH_TYPE_ID
            );

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
}
