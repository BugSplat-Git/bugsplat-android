package com.bugsplat.android;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.*;

public class FeedbackClientTest {

    private MockWebServer server;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    private FeedbackClient createClient() {
        ReportUploader uploader = new ReportUploader("testdb", "testapp", "1.0.0") {
            @Override
            String getBaseUrl() {
                return server.url("").toString();
            }
        };
        return new FeedbackClient("testdb", "testapp", "1.0.0", uploader);
    }

    private void enqueueSuccessfulUpload() {
        String presignedUrl = server.url("/s3-upload").toString();
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"url\": \"" + presignedUrl + "\"}"));
        server.enqueue(new MockResponse().setResponseCode(200));
        server.enqueue(new MockResponse().setResponseCode(200));
    }

    @Test
    public void postFeedback_includesAllFieldsInReport() throws Exception {
        enqueueSuccessfulUpload();

        FeedbackClient client = createClient();
        boolean result = client.postFeedback("Bug Report", "App crashed on login", "alice", "alice@test.com", "key123");

        assertTrue(result);

        // Skip step 1 (getCrashUploadUrl), inspect step 2 (S3 PUT) for the content
        server.takeRequest(); // getCrashUploadUrl
        RecordedRequest putRequest = server.takeRequest();

        String content = extractZipContent(putRequest.getBody().readByteArray(), "feedback.txt");
        assertTrue("should contain title", content.contains("Title: Bug Report"));
        assertTrue("should contain description", content.contains("Description: App crashed on login"));
        assertTrue("should contain user", content.contains("User: alice"));
        assertTrue("should contain email", content.contains("Email: alice@test.com"));
        assertTrue("should contain appKey", content.contains("AppKey: key123"));
    }

    @Test
    public void postFeedback_omitsNullOptionalFields() throws Exception {
        enqueueSuccessfulUpload();

        FeedbackClient client = createClient();
        boolean result = client.postFeedback("Bug Report", null, null, null, null);

        assertTrue(result);

        server.takeRequest();
        RecordedRequest putRequest = server.takeRequest();

        String content = extractZipContent(putRequest.getBody().readByteArray(), "feedback.txt");
        assertTrue("should contain title", content.contains("Title: Bug Report"));
        assertFalse("should not contain Description:", content.contains("Description:"));
        assertFalse("should not contain User:", content.contains("User:"));
        assertFalse("should not contain Email:", content.contains("Email:"));
        assertFalse("should not contain AppKey:", content.contains("AppKey:"));
    }

    @Test
    public void postFeedback_omitsEmptyOptionalFields() throws Exception {
        enqueueSuccessfulUpload();

        FeedbackClient client = createClient();
        boolean result = client.postFeedback("Bug Report", "", "", "", "");

        assertTrue(result);

        server.takeRequest();
        RecordedRequest putRequest = server.takeRequest();

        String content = extractZipContent(putRequest.getBody().readByteArray(), "feedback.txt");
        assertTrue("should contain title", content.contains("Title: Bug Report"));
        assertFalse("should not contain Description:", content.contains("Description:"));
        assertFalse("should not contain User:", content.contains("User:"));
        assertFalse("should not contain Email:", content.contains("Email:"));
        assertFalse("should not contain AppKey:", content.contains("AppKey:"));
    }

    @Test
    public void postFeedback_handlesNullTitle() throws Exception {
        enqueueSuccessfulUpload();

        FeedbackClient client = createClient();
        boolean result = client.postFeedback(null, "some description", null, null, null);

        assertTrue(result);

        server.takeRequest();
        RecordedRequest putRequest = server.takeRequest();

        String content = extractZipContent(putRequest.getBody().readByteArray(), "feedback.txt");
        assertTrue("should contain empty title", content.contains("Title: \n"));
    }

    @Test
    public void postFeedback_commitUsesCorrectCrashType() throws Exception {
        enqueueSuccessfulUpload();

        FeedbackClient client = createClient();
        client.postFeedback("Test", null, null, null, null);

        server.takeRequest(); // getCrashUploadUrl
        server.takeRequest(); // S3 PUT
        RecordedRequest commitRequest = server.takeRequest();

        String body = commitRequest.getBody().readUtf8();
        assertTrue("should use UserFeedback crash type", body.contains("UserFeedback"));
        assertTrue("should use crash type id 36", body.contains("36"));
    }

    @Test
    public void postFeedback_returnsFalseOnUploadFailure() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));

        FeedbackClient client = createClient();
        boolean result = client.postFeedback("Test", null, null, null, null);

        assertFalse(result);
    }

    @Test
    public void postFeedback_withAttachments_uploadsAttachmentAsZipEntry() throws Exception {
        enqueueSuccessfulUpload();

        File tempFile = File.createTempFile("test_attachment", ".txt");
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("attachment content");
        }

        FeedbackClient client = createClient();
        boolean result = client.postFeedback("Bug", "desc", null, null, null, Collections.singletonList(tempFile));

        assertTrue(result);

        server.takeRequest();
        RecordedRequest putRequest = server.takeRequest();

        // The attachment should be uploaded as its own zip entry alongside feedback.txt
        byte[] zipData = putRequest.getBody().readByteArray();
        String attachmentContent = extractZipContent(zipData, tempFile.getName());
        assertEquals("attachment content", attachmentContent);

        // feedback.txt should still exist but should NOT contain the old
        // "Attachment: filename" text (attachments are now real zip entries).
        String feedbackTxt = extractZipContent(zipData, "feedback.txt");
        assertFalse("feedback.txt should not contain stale Attachment: line",
                feedbackTxt.contains("Attachment:"));

        tempFile.delete();
    }

    private String extractZipContent(byte[] zipData, String entryName) throws IOException {
        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData));
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.getName().equals(entryName)) {
                byte[] buffer = new byte[4096];
                StringBuilder sb = new StringBuilder();
                int len;
                while ((len = zis.read(buffer)) != -1) {
                    sb.append(new String(buffer, 0, len, "UTF-8"));
                }
                zis.close();
                return sb.toString();
            }
        }
        zis.close();
        fail("zip entry '" + entryName + "' not found");
        return null;
    }
}
