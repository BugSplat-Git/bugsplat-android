package com.bugsplat.android;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.*;

public class ExceptionReporterTest {

    private MockWebServer server;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        ExceptionReporter.resetRateLimit();
        BugSplatConfig.reset();
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
        ExceptionReporter.resetRateLimit();
        BugSplatConfig.reset();
    }

    private ExceptionReporter createReporter() {
        ReportUploader uploader = new ReportUploader("testdb", "testapp", "1.0.0") {
            @Override
            String getBaseUrl() {
                String url = server.url("").toString();
                return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            }
        };
        return new ExceptionReporter(uploader);
    }

    private void enqueueSuccessfulUpload() {
        String presignedUrl = server.url("/s3-upload").toString();
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"url\": \"" + presignedUrl + "\"}"));
        server.enqueue(new MockResponse().setResponseCode(200));
        server.enqueue(new MockResponse().setResponseCode(200));
    }

    /** A throwable with a deterministic stack, so assertions don't depend on the test runner. */
    private static Throwable throwableWithStack(String message, StackTraceElement... frames) {
        Throwable t = new IllegalStateException(message);
        t.setStackTrace(frames);
        return t;
    }

    private static StackTraceElement frame(String cls, String method, String file, int line) {
        return new StackTraceElement(cls, method, file, line);
    }

    // ---- XML report body ----

    @Test
    public void xmlReport_containsExceptionHeaderFromTopFrame() {
        Throwable t = throwableWithStack("boom",
                frame("com.foo.Bar", "baz", "Bar.java", 42),
                frame("com.foo.App", "run", "App.java", 10));

        String xml = ExceptionReporter.buildXmlReport(t);

        assertTrue(xml.startsWith("<report>"));
        assertTrue("stack key is class.method of the top frame",
                xml.contains("<func><![CDATA[com.foo.Bar.baz]]></func>"));
        assertTrue(xml.contains("<code><![CDATA[boom]]></code>"));
        assertTrue(xml.contains("<file>Bar.java</file>"));
        assertTrue(xml.contains("<line>42</line>"));
    }

    @Test
    public void xmlReport_emitsOneFramePerStackElement() {
        Throwable t = throwableWithStack("boom",
                frame("com.foo.Bar", "baz", "Bar.java", 42),
                frame("com.foo.App", "run", "App.java", 10));

        String xml = ExceptionReporter.buildXmlReport(t);

        assertTrue(xml.contains("framecount=\"2\""));
        assertEquals(2, countOccurrences(xml, "<frame>"));
        assertTrue(xml.contains("<symbol><![CDATA[com.foo.App.run]]></symbol>"));
        assertTrue(xml.contains("<line>10</line>"));
    }

    @Test
    public void xmlReport_usesRootCauseFramesAndKeepsFullChainInExplanation() {
        Throwable cause = throwableWithStack("inner", frame("com.foo.Inner", "fail", "Inner.java", 7));
        Throwable outer = new RuntimeException("outer", cause);
        outer.setStackTrace(new StackTraceElement[]{frame("com.foo.Outer", "wrap", "Outer.java", 99)});

        String xml = ExceptionReporter.buildXmlReport(outer);

        assertTrue("frames come from the root cause",
                xml.contains("<func><![CDATA[com.foo.Inner.fail]]></func>"));
        assertFalse("wrapper frames are not used for grouping",
                xml.contains("<symbol><![CDATA[com.foo.Outer.wrap]]></symbol>"));
        assertTrue("explanation retains the wrapper", xml.contains("java.lang.RuntimeException: outer"));
        assertTrue("explanation retains the cause", xml.contains("Caused by: java.lang.IllegalStateException: inner"));
    }

    @Test
    public void xmlReport_fallsBackToClassNameWhenStackIsEmpty() {
        Throwable t = throwableWithStack("no frames");

        String xml = ExceptionReporter.buildXmlReport(t);

        assertTrue(xml.contains("<func><![CDATA[java.lang.IllegalStateException]]></func>"));
        assertTrue(xml.contains("framecount=\"0\""));
        assertTrue(xml.contains("<line>0</line>"));
    }

    @Test
    public void xmlReport_handlesNullMessageAndNullFileName() {
        Throwable t = new IllegalStateException();
        t.setStackTrace(new StackTraceElement[]{frame("com.foo.Bar", "baz", null, -2)});

        String xml = ExceptionReporter.buildXmlReport(t);

        assertTrue(xml.contains("<code><![CDATA[]]></code>"));
        assertTrue(xml.contains("<file></file>"));
    }

    @Test
    public void xmlReport_splitsCdataTerminatorInsideMessage() {
        Throwable t = throwableWithStack("payload ]]> injected",
                frame("com.foo.Bar", "baz", "Bar.java", 1));

        String xml = ExceptionReporter.buildXmlReport(t);

        assertTrue("a literal ]]> must not close the section early",
                xml.contains("<code><![CDATA[payload ]]]]><![CDATA[> injected]]></code>"));
    }

    @Test
    public void xmlReport_escapesMarkupInFileNames() {
        Throwable t = new IllegalStateException("boom");
        t.setStackTrace(new StackTraceElement[]{frame("com.foo.Bar", "baz", "<Bar>&.java", 1)});

        String xml = ExceptionReporter.buildXmlReport(t);

        assertTrue(xml.contains("<file>&lt;Bar&gt;&amp;.java</file>"));
    }

    @Test
    public void rootCause_stopsOnSelfReferentialChain() {
        Throwable t = new RuntimeException("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertSame(t, ExceptionReporter.rootCause(t));
    }

    // ---- upload ----

    @Test
    public void post_uploadsReportAsStackJdmpEntry() throws Exception {
        enqueueSuccessfulUpload();

        Throwable t = throwableWithStack("boom", frame("com.foo.Bar", "baz", "Bar.java", 42));
        assertTrue(createReporter().post(t, null, null));

        server.takeRequest(); // getCrashUploadUrl
        RecordedRequest putRequest = server.takeRequest();

        String xml = extractZipContent(putRequest.getBody().readByteArray(), "stack.jdmp");
        assertTrue(xml.contains("<func><![CDATA[com.foo.Bar.baz]]></func>"));
    }

    @Test
    public void commitRequest_usesAndroidJavaCrashType() throws Exception {
        enqueueSuccessfulUpload();

        createReporter().post(new IllegalStateException("boom"), null, null);

        server.takeRequest();
        server.takeRequest();
        RecordedRequest commitRequest = server.takeRequest();

        String body = commitRequest.getBody().readUtf8();
        assertTrue("should use Android.Java crash type", body.contains("Android.Java"));
        assertTrue("should use crash type id 4",
                body.contains("name=\"crashTypeId\"\r\n\r\n4\r\n"));
        assertTrue("description mirrors the throwable",
                body.contains("java.lang.IllegalStateException: boom"));
    }

    @Test
    public void commitRequest_mergesInitAttributesWithCallAttributes() throws Exception {
        enqueueSuccessfulUpload();

        Map<String, String> initAttributes = new LinkedHashMap<>();
        initAttributes.put("env", "prod");
        initAttributes.put("tier", "free");
        BugSplatConfig.init("testdb", "testapp", "1.0.0", initAttributes, null);
        BugSplatConfig.setAttribute("level", "3");

        Map<String, String> callAttributes = new LinkedHashMap<>();
        callAttributes.put("tier", "premium"); // per-call wins
        callAttributes.put("screen", "checkout");

        createReporter().post(new IllegalStateException("boom"), callAttributes, null);

        server.takeRequest();
        server.takeRequest();
        String body = server.takeRequest().getBody().readUtf8();

        assertTrue(body.contains("name=\"attributes\""));
        assertTrue("init attribute is carried", body.contains("\"env\":\"prod\""));
        assertTrue("setAttribute value is carried", body.contains("\"level\":\"3\""));
        assertTrue("per-call attribute overrides init", body.contains("\"tier\":\"premium\""));
        assertFalse(body.contains("\"tier\":\"free\""));
        assertTrue(body.contains("\"screen\":\"checkout\""));
    }

    @Test
    public void commitRequest_omitsAttributesWhenNoneSet() throws Exception {
        enqueueSuccessfulUpload();

        createReporter().post(new IllegalStateException("boom"), null, null);

        server.takeRequest();
        server.takeRequest();
        String body = server.takeRequest().getBody().readUtf8();

        assertFalse(body.contains("name=\"attributes\""));
    }

    @Test
    public void post_includesAttachmentsAlongsideTheReport() throws Exception {
        enqueueSuccessfulUpload();

        File tempFile = File.createTempFile("exception_attachment", ".txt");
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("log line");
        }

        assertTrue(createReporter().post(new IllegalStateException("boom"), null,
                Collections.singletonList(tempFile)));

        server.takeRequest();
        byte[] zipData = server.takeRequest().getBody().readByteArray();

        assertEquals("log line", extractZipContent(zipData, tempFile.getName()));
        assertNotNull(extractZipContent(zipData, "stack.jdmp"));

        tempFile.delete();
    }

    @Test
    public void post_returnsFalseOnUploadFailure() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));

        assertFalse(createReporter().post(new IllegalStateException("boom"), null, null));
    }

    @Test
    public void post_returnsFalseForNullThrowable() {
        assertFalse(createReporter().post(null, null, null));
        assertEquals(0, server.getRequestCount());
    }

    // ---- rate limiting ----

    @Test
    public void shouldPost_allowsFirstPost() {
        assertTrue(ExceptionReporter.shouldPost(1_000L));
    }

    @Test
    public void shouldPost_dropsPostsInsideTheInterval() {
        assertTrue(ExceptionReporter.shouldPost(1_000L));
        assertFalse(ExceptionReporter.shouldPost(1_500L));
        assertFalse(ExceptionReporter.shouldPost(
                1_000L + ExceptionReporter.DEFAULT_MIN_POST_INTERVAL_MS - 1));
    }

    @Test
    public void shouldPost_allowsPostAfterTheInterval() {
        assertTrue(ExceptionReporter.shouldPost(1_000L));
        assertTrue(ExceptionReporter.shouldPost(
                1_000L + ExceptionReporter.DEFAULT_MIN_POST_INTERVAL_MS));
    }

    @Test
    public void shouldPost_droppedPostDoesNotExtendTheWindow() {
        assertTrue(ExceptionReporter.shouldPost(1_000L));
        assertFalse(ExceptionReporter.shouldPost(3_000L));
        assertTrue("window is measured from the last accepted post, not the last attempt",
                ExceptionReporter.shouldPost(4_000L));
    }

    @Test
    public void shouldPost_intervalOfZeroDisablesTheGuard() {
        ExceptionReporter.setMinPostIntervalMillis(0);
        assertTrue(ExceptionReporter.shouldPost(1_000L));
        assertTrue(ExceptionReporter.shouldPost(1_000L));
    }

    @Test
    public void setMinPostIntervalMillis_isHonored() {
        ExceptionReporter.setMinPostIntervalMillis(10_000L);
        assertEquals(10_000L, ExceptionReporter.getMinPostIntervalMillis());
        assertTrue(ExceptionReporter.shouldPost(1_000L));
        assertFalse(ExceptionReporter.shouldPost(9_000L));
        assertTrue(ExceptionReporter.shouldPost(11_000L));
    }

    // ---- helpers ----

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index != -1) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
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
