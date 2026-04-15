package com.bugsplat.android;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Implements the BugSplat 3-part upload flow:
 * <ol>
 *   <li>GET /api/getCrashUploadUrl — obtain a presigned S3 URL</li>
 *   <li>PUT the zipped payload to the presigned URL</li>
 *   <li>POST /api/commitS3CrashUpload — commit with metadata</li>
 * </ol>
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
     * Upload a file using the 3-part S3 upload flow.
     *
     * @param file       The file to upload
     * @param crashType  The crash type string (e.g. "Android ANR", "Feedback")
     * @param crashTypeId The crash type ID
     * @return true if the upload succeeded
     */
    boolean upload(File file, String crashType, int crashTypeId) throws IOException {
        byte[] zipped = createZip(file.getName(), new FileInputStream(file));
        return uploadZipped(zipped, file.getName(), crashType, crashTypeId);
    }

    /**
     * Upload raw bytes using the 3-part S3 upload flow.
     *
     * @param data       The data to upload
     * @param fileName   The filename for the zip entry
     * @param crashType  The crash type string (e.g. "Android ANR", "Feedback")
     * @param crashTypeId The crash type ID
     * @return true if the upload succeeded
     */
    boolean upload(byte[] data, String fileName, String crashType, int crashTypeId) throws IOException {
        byte[] zipped = createZip(fileName, new ByteArrayInputStream(data));
        return uploadZipped(zipped, fileName, crashType, crashTypeId);
    }

    private boolean uploadZipped(byte[] zipped, String fileName, String crashType, int crashTypeId) throws IOException {
        String md5 = md5Hex(zipped);

        // Step 1: Get presigned upload URL
        String presignedUrl = getCrashUploadUrl(zipped.length);
        if (presignedUrl == null) {
            Log.e(TAG, "Failed to get crash upload URL");
            return false;
        }
        Log.d(TAG, "Got presigned URL for " + fileName);

        // Step 2: PUT the zip to S3
        if (!uploadToPresignedUrl(presignedUrl, zipped)) {
            Log.e(TAG, "Failed to upload to presigned URL");
            return false;
        }
        Log.d(TAG, "Uploaded " + fileName + " to S3 (" + zipped.length + " bytes)");

        // Step 3: Commit
        if (!commitUpload(presignedUrl, crashType, crashTypeId, md5)) {
            Log.e(TAG, "Failed to commit upload");
            return false;
        }
        Log.i(TAG, "Upload committed: " + fileName + " (" + crashType + ")");

        return true;
    }

    private String getCrashUploadUrl(int size) throws IOException {
        String urlStr = getBaseUrl() + "/api/getCrashUploadUrl"
                + "?database=" + database
                + "&appName=" + application
                + "&appVersion=" + version
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
            if (status != 200) {
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
            if (status != 200) {
                Log.e(TAG, "S3 PUT failed (HTTP " + status + ")");
                return false;
            }
            return true;
        } finally {
            conn.disconnect();
        }
    }

    private boolean commitUpload(String s3Key, String crashType, int crashTypeId, String md5) throws IOException {
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
            writeField(baos, boundary, "database", database);
            writeField(baos, boundary, "appName", application);
            writeField(baos, boundary, "appVersion", version);
            writeField(baos, boundary, "crashType", crashType);
            writeField(baos, boundary, "crashTypeId", String.valueOf(crashTypeId));
            writeField(baos, boundary, "s3key", s3Key);
            writeField(baos, boundary, "md5", md5);
            baos.write(("--" + boundary + "--\r\n").getBytes("UTF-8"));

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

    static byte[] createZip(String entryName, InputStream data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(entryName));
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = data.read(buffer)) != -1) {
                zos.write(buffer, 0, bytesRead);
            }
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
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
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
        baos.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        baos.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes("UTF-8"));
        baos.write(value.getBytes("UTF-8"));
        baos.write("\r\n".getBytes("UTF-8"));
    }
}
