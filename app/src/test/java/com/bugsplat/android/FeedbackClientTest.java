package com.bugsplat.android;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
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
                String url = server.url("").toString();
                return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
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
    public void feedbackBodyIsJson_withTitleAndDescription() throws Exception {
        enqueueSuccessfulUpload();

        FeedbackClient client = createClient();
        boolean result = client.postFeedback("Bug Report", "App crashed on login", null, null, null);

        assertTrue(result);

        server.takeRequest(); // getCrashUploadUrl
        RecordedRequest putRequest = server.takeRequest();

        String json = extractZipContent(putRequest.getBody().readByteArray(), "feedback.json");
        JSONObject parsed = new JSONObject(json);
        assertEquals("Bug Report", parsed.getString("title"));
        assertEquals("App crashed on login", parsed.getString("description"));
    }

    @Test
    public void feedbackBody_omitsDescriptionWhenNullOrEmpty() throws Exception {
        enqueueSuccessfulUpload();

        FeedbackClient client = createClient();
        client.postFeedback("Bug Report", null, null, null, null);

        server.takeRequest();
        RecordedRequest putRequest = server.takeRequest();

        String json = extractZipContent(putRequest.getBody().readByteArray(), "feedback.json");
        JSONObject parsed = new JSONObject(json);
        assertEquals("Bug Report", parsed.getString("title"));
        assertFalse("should not include description when null", parsed.has("description"));
    }

    @Test
    public void feedbackBody_handlesNullTitleAsEmptyString() throws Exception {
        enqueueSuccessfulUpload();

        FeedbackClient client = createClient();
        client.postFeedback(null, "desc", null, null, null);

        server.takeRequest();
        RecordedRequest putRequest = server.takeRequest();

        String json = extractZipContent(putRequest.getBody().readByteArray(), "feedback.json");
        JSONObject parsed = new JSONObject(json);
        assertEquals("", parsed.getString("title"));
    }

    @Test
    public void commitRequest_usesUserDotFeedbackCrashType() throws Exception {
        enqueueSuccessfulUpload();

        FeedbackClient client = createClient();
        client.postFeedback("Test", null, null, null, null);

        server.takeRequest(); // getCrashUploadUrl
        server.takeRequest(); // S3 PUT
        RecordedRequest commitRequest = server.takeRequest();

        String body = commitRequest.getBody().readUtf8();
        assertTrue("should use User.Feedback crash type", body.contains("User.Feedback"));
        assertTrue("should use crash type id 36", body.contains("36"));
    }

    @Test
    public void commitRequest_includesOptionalUserEmailAppKey() throws Exception {
        enqueueSuccessfulUpload();

        FeedbackClient client = createClient();
        client.postFeedback("Title", "some desc", "alice", "alice@test.com", "key123");

        server.takeRequest();
        server.takeRequest();
        RecordedRequest commitRequest = server.takeRequest();

        String body = commitRequest.getBody().readUtf8();
        assertTrue("commit should include user", body.contains("alice"));
        assertTrue("commit should include email", body.contains("alice@test.com"));
        assertTrue("commit should include appKey", body.contains("key123"));
        // description is mirrored on the commit (per User Feedback API docs)
        assertTrue("commit should include description", body.contains("some desc"));
    }

    @Test
    public void commitRequest_includesAttributesAsJsonString() throws Exception {
        enqueueSuccessfulUpload();

        java.util.Map<String, String> attributes = new java.util.LinkedHashMap<>();
        attributes.put("env", "prod");
        attributes.put("tier", "premium");

        FeedbackClient client = createClient();
        client.postFeedback("Title", null, null, null, null, null, attributes);

        server.takeRequest();
        server.takeRequest();
        RecordedRequest commitRequest = server.takeRequest();

        String body = commitRequest.getBody().readUtf8();
        // The attributes field is a JSON string (per the commit API docs).
        assertTrue("commit should include attributes field", body.contains("name=\"attributes\""));
        assertTrue("commit attributes should be JSON-encoded", body.contains("\"env\":\"prod\""));
        assertTrue("commit attributes should include tier", body.contains("\"tier\":\"premium\""));
    }

    @Test
    public void commitRequest_omitsOptionalFieldsWhenNullOrEmpty() throws Exception {
        enqueueSuccessfulUpload();

        FeedbackClient client = createClient();
        client.postFeedback("Title", null, null, null, null);

        server.takeRequest();
        server.takeRequest();
        RecordedRequest commitRequest = server.takeRequest();

        String body = commitRequest.getBody().readUtf8();
        assertFalse("should not include user field", body.contains("name=\"user\""));
        assertFalse("should not include email field", body.contains("name=\"email\""));
        assertFalse("should not include appKey field", body.contains("name=\"appKey\""));
        assertFalse("should not include attributes field", body.contains("name=\"attributes\""));
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

        byte[] zipData = putRequest.getBody().readByteArray();
        String attachmentContent = extractZipContent(zipData, tempFile.getName());
        assertEquals("attachment content", attachmentContent);

        // feedback.json should still be valid JSON with just title/description
        String json = extractZipContent(zipData, "feedback.json");
        JSONObject parsed = new JSONObject(json);
        assertEquals("Bug", parsed.getString("title"));

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
