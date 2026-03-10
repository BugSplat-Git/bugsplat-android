package com.bugsplat.android;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class FeedbackClient {
    private static final String TAG = "BugSplat";
    private static final int FEEDBACK_CRASH_TYPE_ID = 36;

    private final String database;
    private final String application;
    private final String version;

    FeedbackClient(String database, String application, String version) {
        this.database = database;
        this.application = application;
        this.version = version;
    }

    boolean postFeedback(String title, String description, String user, String email) {
        try {
            // Create feedback.json and zip it
            byte[] zipData = createFeedbackZip(title, description);

            // Step 1: Get presigned URL
            String baseUrl = "https://" + database + ".bugsplat.com";
            String getUrlPath = baseUrl + "/api/getCrashUploadUrl"
                    + "?database=" + database
                    + "&appName=" + application
                    + "&appVersion=" + version
                    + "&crashPostSize=" + zipData.length;

            URL getUrl = new URL(getUrlPath);
            HttpURLConnection getConn = (HttpURLConnection) getUrl.openConnection();
            getConn.setRequestMethod("GET");

            int getStatus = getConn.getResponseCode();
            if (getStatus != 200) {
                Log.e(TAG, "Failed to get upload URL. Status: " + getStatus);
                return false;
            }

            String getResponseBody = readResponse(getConn);
            JSONObject getJson = new JSONObject(getResponseBody);
            String presignedUrl = getJson.getString("url");
            getConn.disconnect();

            // Step 2: Upload zip to S3
            URL putUrl = new URL(presignedUrl);
            HttpURLConnection putConn = (HttpURLConnection) putUrl.openConnection();
            putConn.setRequestMethod("PUT");
            putConn.setDoOutput(true);
            putConn.setRequestProperty("Content-Type", "application/zip");
            putConn.setRequestProperty("Content-Length", String.valueOf(zipData.length));

            try (OutputStream os = putConn.getOutputStream()) {
                os.write(zipData);
            }

            int putStatus = putConn.getResponseCode();
            if (putStatus != 200) {
                Log.e(TAG, "Failed to upload to S3. Status: " + putStatus);
                return false;
            }

            String etag = putConn.getHeaderField("ETag");
            if (etag != null) {
                etag = etag.replace("\"", "");
            }
            putConn.disconnect();

            // Step 3: Commit the upload
            String commitUrl = baseUrl + "/api/commitS3CrashUpload";
            String boundary = UUID.randomUUID().toString();

            URL commitUrlObj = new URL(commitUrl);
            HttpURLConnection commitConn = (HttpURLConnection) commitUrlObj.openConnection();
            commitConn.setRequestMethod("POST");
            commitConn.setDoOutput(true);
            commitConn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            try (DataOutputStream dos = new DataOutputStream(commitConn.getOutputStream())) {
                writeFormField(dos, boundary, "database", database);
                writeFormField(dos, boundary, "appName", application);
                writeFormField(dos, boundary, "appVersion", version);
                writeFormField(dos, boundary, "crashTypeId", String.valueOf(FEEDBACK_CRASH_TYPE_ID));
                writeFormField(dos, boundary, "s3Key", presignedUrl);
                writeFormField(dos, boundary, "md5", etag != null ? etag : "");
                writeFormField(dos, boundary, "description", description != null ? description : "");
                writeFormField(dos, boundary, "email", email != null ? email : "");
                writeFormField(dos, boundary, "user", user != null ? user : "");
                dos.writeBytes("--" + boundary + "--\r\n");
            }

            int commitStatus = commitConn.getResponseCode();
            if (commitStatus != 200) {
                Log.e(TAG, "Failed to commit feedback. Status: " + commitStatus);
                return false;
            }

            Log.i(TAG, "Feedback posted successfully!");
            commitConn.disconnect();
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to post feedback", e);
            return false;
        }
    }

    private byte[] createFeedbackZip(String title, String description) throws Exception {
        JSONObject feedbackObj = new JSONObject();
        feedbackObj.put("title", title);
        feedbackObj.put("description", description != null ? description : "");
        byte[] jsonBytes = feedbackObj.toString().getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("feedback.json"));
            zos.write(jsonBytes);
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private void writeFormField(DataOutputStream dos, String boundary, String name, String value) throws Exception {
        dos.writeBytes("--" + boundary + "\r\n");
        dos.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        dos.writeBytes(value + "\r\n");
    }

    private String readResponse(HttpURLConnection conn) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }
}
