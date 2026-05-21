package com.bugsplat.android;

/**
 * The outcome of a User Feedback submission.
 *
 * <p>Returned by the {@code postFeedbackBlockingWithResult} methods on
 * {@link BugSplat}. In addition to the success flag, it surfaces the
 * identifiers BugSplat assigns to the report:</p>
 *
 * <ul>
 *   <li>{@link #getCrashId()} — the numeric report id, suitable for display
 *       (e.g. in a confirmation screen) or correlation.</li>
 *   <li>{@link #getInfoUrl()} — a direct link to the report in the BugSplat
 *       web app.</li>
 * </ul>
 *
 * <p>Both identifiers are nullable. They are {@code null} on failure, and may
 * also be {@code null} on success when the server omits them or the response
 * cannot be parsed — the feedback still uploaded in that case.</p>
 */
public final class FeedbackResult {

    private final boolean success;
    private final Integer crashId;
    private final String infoUrl;

    private FeedbackResult(boolean success, Integer crashId, String infoUrl) {
        this.success = success;
        this.crashId = crashId;
        this.infoUrl = infoUrl;
    }

    /** Build a successful result. {@code crashId} and {@code infoUrl} may be null. */
    public static FeedbackResult success(Integer crashId, String infoUrl) {
        return new FeedbackResult(true, crashId, infoUrl);
    }

    /** Build a failed result with no identifiers. */
    public static FeedbackResult failure() {
        return new FeedbackResult(false, null, null);
    }

    /** @return true if the feedback was uploaded successfully. */
    public boolean isSuccess() {
        return success;
    }

    /** @return the numeric report id assigned by BugSplat, or {@code null} if unavailable. */
    public Integer getCrashId() {
        return crashId;
    }

    /** @return a direct URL to the report in the BugSplat web app, or {@code null} if unavailable. */
    public String getInfoUrl() {
        return infoUrl;
    }

    @Override
    public String toString() {
        return "FeedbackResult{success=" + success
                + ", crashId=" + crashId
                + ", infoUrl=" + infoUrl + '}';
    }
}
