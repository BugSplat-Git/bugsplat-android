package com.bugsplat.android;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.*;

public class ReportUploaderTest {

    // ---- zip tests ----

    @Test
    public void zip_producesValidSingleEntryZip() throws IOException {
        String content = "hello world";
        byte[] zipped = ReportUploader.zip("test.txt", content.getBytes(StandardCharsets.UTF_8));

        assertNotNull(zipped);
        assertTrue(zipped.length > 0);

        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipped));
        ZipEntry entry = zis.getNextEntry();
        assertNotNull(entry);
        assertEquals("test.txt", entry.getName());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = zis.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        assertEquals(content, new String(baos.toByteArray(), StandardCharsets.UTF_8));

        assertNull(zis.getNextEntry());
        zis.close();
    }

    @Test
    public void zip_handlesEmptyInput() throws IOException {
        byte[] zipped = ReportUploader.zip("empty.txt", new byte[0]);

        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipped));
        ZipEntry entry = zis.getNextEntry();
        assertNotNull(entry);
        assertEquals("empty.txt", entry.getName());
        zis.close();
    }

    // ---- md5Hex tests ----

    @Test
    public void md5Hex_returnsCorrectHashForKnownInput() {
        String hash = ReportUploader.md5Hex("hello world".getBytes(StandardCharsets.UTF_8));
        assertEquals("5eb63bbbe01eeed093cb22bb8f5acdc3", hash);
    }

    @Test
    public void md5Hex_returnsCorrectHashForEmptyInput() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", ReportUploader.md5Hex(new byte[0]));
    }

    @Test
    public void md5Hex_returns32CharLowercaseHex() {
        String hash = ReportUploader.md5Hex("test data".getBytes(StandardCharsets.UTF_8));
        assertEquals(32, hash.length());
        assertTrue(hash.matches("[0-9a-f]+"));
    }

    // ---- readBody tests ----

    @Test
    public void readBody_readsInputStream() throws IOException {
        String content = "response body content";
        InputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        assertEquals(content, ReportUploader.readBody(stream));
    }

    @Test
    public void readBody_returnsEmptyForNullStream() throws IOException {
        assertEquals("", ReportUploader.readBody(null));
    }

    @Test
    public void readBody_handlesEmptyStream() throws IOException {
        assertEquals("", ReportUploader.readBody(new ByteArrayInputStream(new byte[0])));
    }

    // ---- 3-step upload integration tests ----

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

    private CommitOptions anrOptions() {
        return new CommitOptions().crashType("Android.ANR").crashTypeId(37);
    }

    private byte[] sampleZip() throws IOException {
        return ReportUploader.zip("test.txt", "test content".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void upload_performsThreeStepFlow() throws Exception {
        String presignedUrl = server.url("/s3-upload").toString();

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"url\": \"" + presignedUrl + "\"}"));
        server.enqueue(new MockResponse().setResponseCode(200));
        server.enqueue(new MockResponse().setResponseCode(200));

        ReportUploader uploader = new TestableReportUploader("testdb", "testapp", "1.0.0", server);
        boolean result = uploader.upload(sampleZip(), anrOptions());

        assertTrue("upload should succeed", result);
        assertEquals(3, server.getRequestCount());

        // Step 1
        RecordedRequest req1 = server.takeRequest();
        assertEquals("GET", req1.getMethod());
        assertTrue(req1.getPath().contains("getCrashUploadUrl"));
        assertTrue(req1.getPath().contains("database=testdb"));
        assertTrue(req1.getPath().contains("appName=testapp"));
        assertTrue(req1.getPath().contains("appVersion=1.0.0"));

        // Step 2
        RecordedRequest req2 = server.takeRequest();
        assertEquals("PUT", req2.getMethod());
        assertEquals("application/octet-stream", req2.getHeader("Content-Type"));

        // Step 3
        RecordedRequest req3 = server.takeRequest();
        assertEquals("POST", req3.getMethod());
        assertTrue(req3.getPath().contains("commitS3CrashUpload"));
        String commitBody = req3.getBody().readUtf8();
        assertTrue(commitBody.contains("testdb"));
        assertTrue(commitBody.contains("testapp"));
        assertTrue(commitBody.contains("1.0.0"));
        assertTrue(commitBody.contains("Android.ANR"));
        assertTrue(commitBody.contains("37"));
        assertTrue("should use s3Key (capital K)", commitBody.contains("name=\"s3Key\""));
        assertTrue(commitBody.contains("name=\"md5\""));
    }

    @Test
    public void upload_returnsFalseWhenGetUrlFails() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));
        ReportUploader uploader = new TestableReportUploader("testdb", "testapp", "1.0.0", server);
        assertFalse(uploader.upload(sampleZip(), anrOptions()));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void upload_returnsFalseWhenRateLimited() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429));
        ReportUploader uploader = new TestableReportUploader("testdb", "testapp", "1.0.0", server);
        assertFalse(uploader.upload(sampleZip(), anrOptions()));
    }

    @Test
    public void upload_returnsFalseWhenS3PutFails() throws Exception {
        String presignedUrl = server.url("/s3-upload").toString();
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"url\": \"" + presignedUrl + "\"}"));
        server.enqueue(new MockResponse().setResponseCode(403));

        ReportUploader uploader = new TestableReportUploader("testdb", "testapp", "1.0.0", server);
        assertFalse(uploader.upload(sampleZip(), anrOptions()));
        assertEquals(2, server.getRequestCount());
    }

    @Test
    public void upload_returnsFalseWhenCommitFails() throws Exception {
        String presignedUrl = server.url("/s3-upload").toString();
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"url\": \"" + presignedUrl + "\"}"));
        server.enqueue(new MockResponse().setResponseCode(200));
        server.enqueue(new MockResponse().setResponseCode(500));

        ReportUploader uploader = new TestableReportUploader("testdb", "testapp", "1.0.0", server);
        assertFalse(uploader.upload(sampleZip(), anrOptions()));
        assertEquals(3, server.getRequestCount());
    }

    @Test
    public void upload_includesAllCommitOptionsFields() throws Exception {
        String presignedUrl = server.url("/s3-upload").toString();
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"url\": \"" + presignedUrl + "\"}"));
        server.enqueue(new MockResponse().setResponseCode(200));
        server.enqueue(new MockResponse().setResponseCode(200));

        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("env", "prod");

        CommitOptions options = new CommitOptions()
                .crashType("User.Feedback")
                .crashTypeId(36)
                .user("alice")
                .email("alice@test.com")
                .appKey("key123")
                .description("bug desc")
                .notes("build 42")
                .attributes(attrs);

        ReportUploader uploader = new TestableReportUploader("testdb", "testapp", "1.0.0", server);
        assertTrue(uploader.upload(sampleZip(), options));

        server.takeRequest();
        server.takeRequest();
        RecordedRequest commitRequest = server.takeRequest();
        String body = commitRequest.getBody().readUtf8();

        assertTrue(body.contains("name=\"user\""));
        assertTrue(body.contains("alice"));
        assertTrue(body.contains("name=\"email\""));
        assertTrue(body.contains("alice@test.com"));
        assertTrue(body.contains("name=\"appKey\""));
        assertTrue(body.contains("key123"));
        assertTrue(body.contains("name=\"description\""));
        assertTrue(body.contains("bug desc"));
        assertTrue(body.contains("name=\"notes\""));
        assertTrue(body.contains("build 42"));
        assertTrue(body.contains("name=\"attributes\""));
        assertTrue("attributes should be JSON-encoded", body.contains("\"env\":\"prod\""));
    }

    @Test
    public void upload_rejectsEmptyPayload() throws Exception {
        ReportUploader uploader = new TestableReportUploader("testdb", "testapp", "1.0.0", server);
        try {
            uploader.upload(new byte[0], anrOptions());
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    /**
     * Test subclass that routes all HTTP calls to the MockWebServer.
     */
    static class TestableReportUploader extends ReportUploader {
        private final String baseUrl;

        TestableReportUploader(String database, String application, String version, MockWebServer server) {
            super(database, application, version);
            // MockWebServer's server.url("").toString() ends with a "/"; strip
            // it so URL joining in ReportUploader produces clean paths.
            String url = server.url("").toString();
            this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }

        @Override
        String getBaseUrl() {
            return baseUrl;
        }
    }
}
