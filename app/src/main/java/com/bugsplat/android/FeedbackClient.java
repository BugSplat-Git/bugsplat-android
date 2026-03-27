package com.bugsplat.android;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.UUID;

class FeedbackClient {
    private static final String TAG = "BugSplat";

    private final String database;
    private final String application;
    private final String version;

    FeedbackClient(String database, String application, String version) {
        this.database = database;
        this.application = application;
        this.version = version;
    }

    boolean postFeedback(String title, String description, String user, String email, String appKey) {
        return postFeedback(title, description, user, email, appKey, null);
    }

    boolean postFeedback(String title, String description, String user, String email, String appKey, List<File> attachments) {
        try {
            String url = "https://" + database + ".bugsplat.com/post/feedback/";
            String boundary = UUID.randomUUID().toString();

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            // Required fields
            writeFormField(dos, boundary, "database", database);
            writeFormField(dos, boundary, "appName", application);
            writeFormField(dos, boundary, "appVersion", version);
            writeFormField(dos, boundary, "title", title);

            // Optional fields
            if (description != null && !description.isEmpty()) {
                writeFormField(dos, boundary, "description", description);
            }
            if (user != null && !user.isEmpty()) {
                writeFormField(dos, boundary, "user", user);
            }
            if (email != null && !email.isEmpty()) {
                writeFormField(dos, boundary, "email", email);
            }
            if (appKey != null && !appKey.isEmpty()) {
                writeFormField(dos, boundary, "appKey", appKey);
            }

            // File attachments
            if (attachments != null) {
                for (File file : attachments) {
                    if (file == null || !file.exists() || !file.isFile()) {
                        Log.w(TAG, "Skipping invalid attachment: " + file);
                        continue;
                    }
                    writeFileField(dos, boundary, file.getName(), file);
                    Log.d(TAG, "Attached file " + file.getName() + " (" + file.length() + " bytes)");
                }
            }

            dos.writeBytes("--" + boundary + "--\r\n");
            dos.flush();

            byte[] payload = baos.toByteArray();
            conn.setRequestProperty("Content-Length", String.valueOf(payload.length));
            conn.getOutputStream().write(payload);
            conn.getOutputStream().flush();

            int status = conn.getResponseCode();
            conn.disconnect();

            if (status >= 200 && status < 300) {
                Log.i(TAG, "Feedback posted successfully (HTTP " + status + ")");
                return true;
            } else {
                Log.e(TAG, "Failed to post feedback (HTTP " + status + ")");
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to post feedback", e);
            return false;
        }
    }

    private void writeFormField(DataOutputStream dos, String boundary, String name, String value) throws Exception {
        dos.writeBytes("--" + boundary + "\r\n");
        dos.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        dos.write(value.getBytes("UTF-8"));
        dos.writeBytes("\r\n");
    }

    private void writeFileField(DataOutputStream dos, String boundary, String fieldName, File file) throws Exception {
        dos.writeBytes("--" + boundary + "\r\n");
        dos.writeBytes("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + file.getName() + "\"\r\n");
        dos.writeBytes("Content-Type: application/octet-stream\r\n\r\n");

        byte[] buffer = new byte[4096];
        try (FileInputStream fis = new FileInputStream(file)) {
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                dos.write(buffer, 0, bytesRead);
            }
        }
        dos.writeBytes("\r\n");
    }
}
