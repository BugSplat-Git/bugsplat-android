package com.bugsplat.android;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Posts User Feedback reports to BugSplat via the 3-part presigned-URL flow.
 *
 * The feedback body is a JSON document ({@code feedback.json}) containing
 * {@code title} and (optionally) {@code description}. Metadata like
 * {@code user}, {@code email}, and {@code appKey} are attached on the
 * {@code commitS3CrashUpload} request — not baked into the JSON body.
 *
 * See <a href="https://docs.bugsplat.com/introduction/development/web-services/user-feedback">
 * BugSplat User Feedback docs</a>.
 */
class FeedbackClient {
    private static final String TAG = "BugSplat";
    private static final String CRASH_TYPE = "User.Feedback";
    private static final int CRASH_TYPE_ID = 36;

    private final ReportUploader uploader;

    FeedbackClient(String database, String application, String version) {
        this(database, application, version, new ReportUploader(database, application, version));
    }

    /** Package-private constructor for testing with a custom uploader. */
    FeedbackClient(String database, String application, String version, ReportUploader uploader) {
        this.uploader = uploader;
    }

    boolean postFeedback(String title, String description, String user, String email, String appKey) {
        return postFeedback(title, description, user, email, appKey, null, null);
    }

    boolean postFeedback(String title, String description, String user, String email, String appKey,
                         List<File> attachments) {
        return postFeedback(title, description, user, email, appKey, attachments, null);
    }

    boolean postFeedback(String title, String description, String user, String email, String appKey,
                         List<File> attachments, Map<String, String> attributes) {
        try {
            // feedback.json — per the User Feedback API, only title (required)
            // and description (optional) live in the JSON; the rest are
            // commit-request fields below.
            JSONObject json = new JSONObject();
            json.put("title", title != null ? title : "");
            if (description != null && !description.isEmpty()) {
                json.put("description", description);
            }
            byte[] jsonBytes = json.toString().getBytes(StandardCharsets.UTF_8);

            byte[] zipped = buildZip(jsonBytes, attachments);

            CommitOptions options = new CommitOptions()
                    .crashType(CRASH_TYPE)
                    .crashTypeId(CRASH_TYPE_ID)
                    .user(user)
                    .email(email)
                    .description(description)
                    .appKey(appKey)
                    .attributes(attributes);

            boolean success = uploader.upload(zipped, options);
            if (success) {
                Log.i(TAG, "Feedback posted successfully");
            } else {
                Log.e(TAG, "Failed to post feedback");
            }
            return success;

        } catch (JSONException | IOException e) {
            Log.e(TAG, "Failed to post feedback", e);
            return false;
        }
    }

    /**
     * Build the feedback zip: feedback.json first, then each attachment
     * streamed directly from disk (not buffered fully in memory).
     * Attachment filenames are used as-is for zip entry names — callers are
     * responsible for ensuring they don't collide with each other or with
     * {@code feedback.json}.
     */
    private static byte[] buildZip(byte[] feedbackJson, List<File> attachments) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("feedback.json"));
            zos.write(feedbackJson);
            zos.closeEntry();

            if (attachments != null) {
                byte[] buffer = new byte[8192];
                for (File file : attachments) {
                    if (file == null || !file.exists() || !file.isFile()) {
                        Log.w(TAG, "Skipping invalid attachment: " + file);
                        continue;
                    }
                    zos.putNextEntry(new ZipEntry(file.getName()));
                    try (FileInputStream fis = new FileInputStream(file)) {
                        int n;
                        while ((n = fis.read(buffer)) != -1) {
                            zos.write(buffer, 0, n);
                        }
                    }
                    zos.closeEntry();
                }
            }
        }
        return baos.toByteArray();
    }
}
