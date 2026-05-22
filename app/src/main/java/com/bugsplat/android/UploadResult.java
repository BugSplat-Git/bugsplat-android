package com.bugsplat.android;

/**
 * Internal carrier for the outcome of a {@link ReportUploader} upload.
 *
 * <p>Holds the success flag plus the identifiers parsed from the
 * {@code commitS3CrashUpload} response ({@code crashId} and {@code infoUrl}).
 * Both identifiers are nullable: they are absent when the server omits them or
 * when the response body cannot be parsed even though the upload succeeded.</p>
 *
 * <p>This type stays package-private — the public-facing equivalent is
 * {@link FeedbackResult}.</p>
 */
class UploadResult {
    final boolean success;
    final Integer crashId;
    final String infoUrl;

    UploadResult(boolean success, Integer crashId, String infoUrl) {
        this.success = success;
        this.crashId = crashId;
        this.infoUrl = infoUrl;
    }

    /** A failed upload with no identifiers. */
    static UploadResult failure() {
        return new UploadResult(false, null, null);
    }

    /** A successful upload carrying the parsed identifiers (either may be null). */
    static UploadResult success(Integer crashId, String infoUrl) {
        return new UploadResult(true, crashId, infoUrl);
    }
}
