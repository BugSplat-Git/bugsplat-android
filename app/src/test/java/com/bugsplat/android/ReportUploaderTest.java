package com.bugsplat.android;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.*;

public class ReportUploaderTest {

    // ---- createZip tests ----

    @Test
    public void createZip_producesValidZipWithCorrectEntry() throws IOException {
        String content = "hello world";
        byte[] zipped = ReportUploader.createZip(
                "test.txt",
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))
        );

        assertNotNull(zipped);
        assertTrue(zipped.length > 0);

        // Read back the zip and verify
        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipped));
        ZipEntry entry = zis.getNextEntry();
        assertNotNull("zip should contain an entry", entry);
        assertEquals("test.txt", entry.getName());

        // Verify content
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = zis.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        assertEquals(content, baos.toString("UTF-8"));

        // Should be only one entry
        assertNull("zip should contain exactly one entry", zis.getNextEntry());
        zis.close();
    }

    @Test
    public void createZip_handlesEmptyInput() throws IOException {
        byte[] zipped = ReportUploader.createZip(
                "empty.txt",
                new ByteArrayInputStream(new byte[0])
        );

        assertNotNull(zipped);

        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipped));
        ZipEntry entry = zis.getNextEntry();
        assertNotNull(entry);
        assertEquals("empty.txt", entry.getName());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = zis.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        assertEquals(0, baos.size());
        zis.close();
    }

    @Test
    public void createZip_handlesLargeInput() throws IOException {
        byte[] largeContent = new byte[100_000];
        for (int i = 0; i < largeContent.length; i++) {
            largeContent[i] = (byte) (i % 256);
        }

        byte[] zipped = ReportUploader.createZip(
                "large.bin",
                new ByteArrayInputStream(largeContent)
        );

        // Zip should be smaller due to compression of repeating pattern
        assertNotNull(zipped);

        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipped));
        ZipEntry entry = zis.getNextEntry();
        assertNotNull(entry);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = zis.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        assertArrayEquals(largeContent, baos.toByteArray());
        zis.close();
    }

    // ---- md5Hex tests ----

    @Test
    public void md5Hex_returnsCorrectHashForKnownInput() {
        // MD5 of "hello world" is 5eb63bbbe01eeed093cb22bb8f5acdc3
        String hash = ReportUploader.md5Hex("hello world".getBytes(StandardCharsets.UTF_8));
        assertEquals("5eb63bbbe01eeed093cb22bb8f5acdc3", hash);
    }

    @Test
    public void md5Hex_returnsCorrectHashForEmptyInput() {
        // MD5 of empty string is d41d8cd98f00b204e9800998ecf8427e
        String hash = ReportUploader.md5Hex(new byte[0]);
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", hash);
    }

    @Test
    public void md5Hex_returns32CharLowercaseHex() {
        String hash = ReportUploader.md5Hex("test data".getBytes(StandardCharsets.UTF_8));
        assertEquals(32, hash.length());
        assertTrue("hash should be lowercase hex", hash.matches("[0-9a-f]+"));
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
        InputStream stream = new ByteArrayInputStream(new byte[0]);
        assertEquals("", ReportUploader.readBody(stream));
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

    @Test
    public void upload_performsThreeStepFlow() throws Exception {
        String presignedUrl = server.url("/s3-upload").toString();

        // Step 1: getCrashUploadUrl response
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"url\": \"" + presignedUrl + "\"}"));

        // Step 2: S3 PUT response
        server.enqueue(new MockResponse().setResponseCode(200));

        // Step 3: commitS3CrashUpload response
        server.enqueue(new MockResponse().setResponseCode(200));

        // Use a ReportUploader that points at our mock server
        String baseUrl = server.url("").toString();
        // We need to extract host:port to construct the uploader
        // The uploader constructs URLs like https://{database}.bugsplat.com/...
        // So we'll use a TestableReportUploader subclass approach
        // Actually, let's test the utility methods above and the flow separately

        // For the full flow test, we verify the 3 requests arrive in order
        ReportUploader uploader = new TestableReportUploader("testdb", "testapp", "1.0.0", server);
        boolean result = uploader.upload(
                "test content".getBytes(StandardCharsets.UTF_8),
                "test.txt",
                "Android ANR",
                37
        );

        assertTrue("upload should succeed", result);
        assertEquals("should make 3 requests", 3, server.getRequestCount());

        // Verify Step 1: getCrashUploadUrl
        RecordedRequest req1 = server.takeRequest();
        assertEquals("GET", req1.getMethod());
        assertTrue(req1.getPath().contains("getCrashUploadUrl"));
        assertTrue(req1.getPath().contains("database=testdb"));
        assertTrue(req1.getPath().contains("appName=testapp"));
        assertTrue(req1.getPath().contains("appVersion=1.0.0"));

        // Verify Step 2: S3 PUT
        RecordedRequest req2 = server.takeRequest();
        assertEquals("PUT", req2.getMethod());
        assertEquals("application/octet-stream", req2.getHeader("Content-Type"));

        // Verify the body is a valid zip
        byte[] putBody = req2.getBody().readByteArray();
        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(putBody));
        ZipEntry entry = zis.getNextEntry();
        assertNotNull(entry);
        assertEquals("test.txt", entry.getName());
        zis.close();

        // Verify Step 3: commitS3CrashUpload
        RecordedRequest req3 = server.takeRequest();
        assertEquals("POST", req3.getMethod());
        assertTrue(req3.getPath().contains("commitS3CrashUpload"));
        String commitBody = req3.getBody().readUtf8();
        assertTrue("should contain database", commitBody.contains("testdb"));
        assertTrue("should contain appName", commitBody.contains("testapp"));
        assertTrue("should contain appVersion", commitBody.contains("1.0.0"));
        assertTrue("should contain crashType", commitBody.contains("Android ANR"));
        assertTrue("should contain crashTypeId", commitBody.contains("37"));
        assertTrue("should contain md5", commitBody.contains("md5"));
    }

    @Test
    public void upload_returnsFalseWhenGetUrlFails() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));

        ReportUploader uploader = new TestableReportUploader("testdb", "testapp", "1.0.0", server);
        boolean result = uploader.upload(
                "test".getBytes(StandardCharsets.UTF_8),
                "test.txt",
                "Android ANR",
                37
        );

        assertFalse("upload should fail when getCrashUploadUrl fails", result);
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void upload_returnsFalseWhenRateLimited() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429));

        ReportUploader uploader = new TestableReportUploader("testdb", "testapp", "1.0.0", server);
        boolean result = uploader.upload(
                "test".getBytes(StandardCharsets.UTF_8),
                "test.txt",
                "Android ANR",
                37
        );

        assertFalse("upload should fail when rate limited", result);
    }

    @Test
    public void upload_returnsFalseWhenS3PutFails() throws Exception {
        String presignedUrl = server.url("/s3-upload").toString();

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"url\": \"" + presignedUrl + "\"}"));
        server.enqueue(new MockResponse().setResponseCode(403));

        ReportUploader uploader = new TestableReportUploader("testdb", "testapp", "1.0.0", server);
        boolean result = uploader.upload(
                "test".getBytes(StandardCharsets.UTF_8),
                "test.txt",
                "Android ANR",
                37
        );

        assertFalse("upload should fail when S3 PUT fails", result);
        assertEquals(2, server.getRequestCount());
    }

    @Test
    public void upload_returnsFalseWhenCommitFails() throws Exception {
        String presignedUrl = server.url("/s3-upload").toString();

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"url\": \"" + presignedUrl + "\"}"));
        server.enqueue(new MockResponse().setResponseCode(200));
        server.enqueue(new MockResponse().setResponseCode(500));

        ReportUploader uploader = new TestableReportUploader("testdb", "testapp", "1.0.0", server);
        boolean result = uploader.upload(
                "test".getBytes(StandardCharsets.UTF_8),
                "test.txt",
                "Android ANR",
                37
        );

        assertFalse("upload should fail when commit fails", result);
        assertEquals(3, server.getRequestCount());
    }

    /**
     * Test subclass that routes all HTTP calls to the MockWebServer
     * instead of the real BugSplat API.
     */
    static class TestableReportUploader extends ReportUploader {
        private final String baseUrl;

        TestableReportUploader(String database, String application, String version, MockWebServer server) {
            super(database, application, version);
            // MockWebServer URLs include a trailing slash; strip it since
            // ReportUploader appends paths starting with "/".
            String url = server.url("").toString();
            this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }

        @Override
        String getBaseUrl() {
            return baseUrl;
        }
    }
}
