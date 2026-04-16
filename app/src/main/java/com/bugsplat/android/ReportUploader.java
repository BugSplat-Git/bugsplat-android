package com.bugsplat.android;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Implements the BugSplat 3-part upload flow:
 * <ol>
 *   <li>GET /api/getCrashUploadUrl — obtain a presigned S3 URL</li>
 *   <li>PUT the zipped payload to the presigned URL</li>
 *   <li>POST /api/commitS3CrashUpload — commit with metadata (see {@link CommitOptions})</li>
 * </ol>
 *
 * Callers are responsible for producing the zip. Use {@link #zip(String, byte[])}
 * for the common single-entry case, or build a {@link ZipOutputStream} inline
 * (e.g. to stream file attachments without reading them fully into memory).
 *
 * https://docs.bugsplat.com/introduction/development/web-services/crash
 */
class ReportUploader {
    private static final String TAG = "BugSplat-Upload";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private final String database;
    private final String application;
    private final String version;

    ReportUploader(String database, String application, String version) {
        this.database = database;
        this.application = application;
        this.version = version;
    }

    /** Returns the base URL for API calls. Overridable for testing. */
    String getBaseUrl() {
        return "https://" + database + ".bugsplat.com";
    }

    /**
     * Upload a pre-built zip using the 3-part S3 upload flow.
     *
     * @param zipped The zipped payload bytes to upload
     * @param options Commit-request fields (crashType, user, email, attributes, etc.)
     * @return true if all three steps succeeded
     */
    boolean upload(byte[] zipped, CommitOptions options) throws IOException {
        if (zipped == null || zipped.length == 0) {
            throw new IllegalArgumentException("zipped payload must not be empty");
        }
        String md5 = md5Hex(zipped);

        // Step 1: Get presigned upload URL
        String presignedUrl = getCrashUploadUrl(zipped.length);
        if (presignedUrl == null) {
            Log.e(TAG, "Failed to get crash upload URL");
            return false;
        }

        // Step 2: PUT the zip to S3
        if (!uploadToPresignedUrl(presignedUrl, zipped)) {
            Log.e(TAG, "Failed to upload to presigned URL");
            return false;
        }
        Log.d(TAG, "Uploaded " + zipped.length + " bytes to S3");

        // Step 3: Commit
        if (!commitUpload(presignedUrl, md5, options)) {
            Log.e(TAG, "Failed to commit upload");
            return false;
        }
        Log.i(TAG, "Upload committed"
                + (options != null && options.crashType != null ? " (" + options.crashType + ")" : ""));
        return true;
    }

    private String getCrashUploadUrl(int size) throws IOException {
        String urlStr = getBaseUrl() + "/api/getCrashUploadUrl"
                + "?database=" + urlEncode(database)
                + "&appName=" + urlEncode(application)
                + "&appVersion=" + urlEncode(version)
                + "&crashPostSize=" + size;

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int status = conn.getResponseCode();
            if (status == 429) {
                Log.w(TAG, "Rate limited by server");
                return null;
            }
            if (status < 200 || status >= 300) {
                Log.e(TAG, "getCrashUploadUrl failed (HTTP " + status + ")");
                return null;
            }

            String body = readBody(conn.getInputStream());
            JSONObject json = new JSONObject(body);
            return json.getString("url");
        } catch (Exception e) {
            Log.e(TAG, "getCrashUploadUrl error", e);
            return null;
        } finally {
            conn.disconnect();
        }
    }

    private boolean uploadToPresignedUrl(String presignedUrl, byte[] data) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(presignedUrl).openConnection();
        try {
            conn.setRequestMethod("PUT");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setRequestProperty("Content-Length", String.valueOf(data.length));
            conn.setFixedLengthStreamingMode(data.length);

            try (OutputStream out = conn.getOutputStream()) {
                out.write(data);
                out.flush();
            }

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                Log.e(TAG, "S3 PUT failed (HTTP " + status + ")");
                return false;
            }
            return true;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Build the multipart/form-data body for commitS3CrashUpload. Field names
     * mirror the documented BugSplat API 1-to-1:
     * https://docs.bugsplat.com/introduction/development/web-services/crash#request-body-multipart-form-data
     */
    private boolean commitUpload(String s3Key, String md5, CommitOptions options) throws IOException {
        String urlStr = getBaseUrl() + "/api/commitS3CrashUpload";
        String boundary = java.util.UUID.randomUUID().toString();

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            // Fields always set by the uploader itself
            writeField(baos, boundary, "database", database);
            writeField(baos, boundary, "appName", application);
            writeField(baos, boundary, "appVersion", version);
            writeField(baos, boundary, "s3Key", s3Key);
            writeField(baos, boundary, "md5", md5);

            // Optional fields from CommitOptions — mirrors the documented
            // commitS3CrashUpload multipart body 1-to-1.
            if (options != null) {
                writeOptionalField(baos, boundary, "crashType", options.crashType);
                writeOptionalField(baos, boundary, "crashTypeId",
                        options.crashTypeId != null ? options.crashTypeId.toString() : null);
                writeOptionalField(baos, boundary, "fullDumpFlag",
                        options.fullDumpFlag != null ? options.fullDumpFlag.toString() : null);
                writeOptionalField(baos, boundary, "appKey", options.appKey);
                writeOptionalField(baos, boundary, "description", options.description);
                writeOptionalField(baos, boundary, "user", options.user);
                writeOptionalField(baos, boundary, "email", options.email);
                writeOptionalField(baos, boundary, "internalIP", options.internalIP);
                writeOptionalField(baos, boundary, "notes", options.notes);
                writeOptionalField(baos, boundary, "processor", options.processor);
                writeOptionalField(baos, boundary, "crashTime", options.crashTime);
                writeOptionalField(baos, boundary, "attributes", options.attributesJson());
                writeOptionalField(baos, boundary, "crashHash", options.crashHash);
            }
            baos.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            byte[] payload = baos.toByteArray();
            conn.setRequestProperty("Content-Length", String.valueOf(payload.length));
            conn.setFixedLengthStreamingMode(payload.length);

            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
                out.flush();
            }

            int status = conn.getResponseCode();
            if (status >= 200 && status < 300) {
                return true;
            } else {
                String body = readBody(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
                Log.e(TAG, "commitS3CrashUpload failed (HTTP " + status + "): " + body);
                return false;
            }
        } finally {
            conn.disconnect();
        }
    }

    // -- Utilities --

    /** Convenience: build a single-entry zip around {@code data}. */
    static byte[] zip(String entryName, byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(data);
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    static String md5Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }

    static String readBody(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private static void writeField(ByteArrayOutputStream baos, String boundary,
                                   String name, String value) throws IOException {
        baos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        baos.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        baos.write(value.getBytes(StandardCharsets.UTF_8));
        baos.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeOptionalField(ByteArrayOutputStream baos, String boundary,
                                           String name, String value) throws IOException {
        if (value != null && !value.isEmpty()) {
            writeField(baos, boundary, name, value);
        }
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new AssertionError(e); // UTF-8 always supported
        }
    }
}
