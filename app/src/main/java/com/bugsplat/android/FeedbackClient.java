package com.bugsplat.android;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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
        return postFeedbackWithResult(title, description, user, email, appKey, attachments, attributes)
                .isSuccess();
    }

    /**
     * Post feedback and return a {@link FeedbackResult} carrying the report
     * identifiers from the commit response. Equivalent to
     * {@link #postFeedback(String, String, String, String, String, List, Map)}
     * but surfaces {@code crashId}/{@code infoUrl} instead of a bare boolean.
     */
    FeedbackResult postFeedbackWithResult(String title, String description, String user, String email,
                                          String appKey, List<File> attachments,
                                          Map<String, String> attributes) {
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

            byte[] zipped = ReportUploader.zip("feedback.json", jsonBytes, attachments);

            CommitOptions options = new CommitOptions()
                    .crashType(CRASH_TYPE)
                    .crashTypeId(CRASH_TYPE_ID)
                    .user(user)
                    .email(email)
                    .description(description)
                    .appKey(appKey)
                    .attributes(attributes);

            UploadResult result = uploader.uploadWithResult(zipped, options);
            if (result.success) {
                Log.i(TAG, "Feedback posted successfully");
                return FeedbackResult.success(result.crashId, result.infoUrl);
            } else {
                Log.e(TAG, "Failed to post feedback");
                return FeedbackResult.failure();
            }

        } catch (JSONException | IOException e) {
            Log.e(TAG, "Failed to post feedback", e);
            return FeedbackResult.failure();
        }
    }
}
