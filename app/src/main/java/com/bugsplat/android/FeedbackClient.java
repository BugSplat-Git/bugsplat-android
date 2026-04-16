package com.bugsplat.android;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Posts User Feedback reports to BugSplat via the 3-part presigned-URL flow.
 *
 * The feedback body is a JSON document ({@code feedback.json}) containing
 * {@code title} and (optionally) {@code description}. Metadata like
 * {@code user}, {@code email}, and {@code appKey} is attached on the
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
            // Build the feedback.json body — only title (required) and description (optional).
            // The other fields (user, email, description, appKey, attributes) go on the commit request.
            JSONObject json = new JSONObject();
            json.put("title", title != null ? title : "");
            if (description != null && !description.isEmpty()) {
                json.put("description", description);
            }

            // LinkedHashMap preserves iteration order so feedback.json is first in the zip.
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("feedback.json", json.toString().getBytes(StandardCharsets.UTF_8));

            if (attachments != null) {
                for (File file : attachments) {
                    if (file == null || !file.exists() || !file.isFile()) {
                        Log.w(TAG, "Skipping invalid attachment: " + file);
                        continue;
                    }
                    entries.put(uniqueEntryName(entries, file.getName()), readFileBytes(file));
                }
            }

            // Extra commit fields per the User Feedback / Crash API docs.
            Map<String, String> commitFields = new LinkedHashMap<>();
            if (user != null && !user.isEmpty()) commitFields.put("user", user);
            if (email != null && !email.isEmpty()) commitFields.put("email", email);
            if (description != null && !description.isEmpty()) commitFields.put("description", description);
            if (appKey != null && !appKey.isEmpty()) commitFields.put("appKey", appKey);
            if (attributes != null && !attributes.isEmpty()) {
                // Per the API docs, `attributes` is a JSON string of custom attributes.
                commitFields.put("attributes", new JSONObject(attributes).toString());
            }

            boolean success = uploader.upload(entries, CRASH_TYPE, CRASH_TYPE_ID, commitFields);
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
