package com.bugsplat.android;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class AttachmentPathTest {

    @Test
    public void validate_acceptsAbsolutePath() {
        AttachmentPath.validate("/data/user/0/com.example/files/log.txt");
    }

    @Test
    public void validate_rejectsNull() {
        assertIllegalPath(null, "Attachment path must not be null or blank");
    }

    @Test
    public void validate_rejectsBlank() {
        assertIllegalPath("  ", "Attachment path must not be null or blank");
        assertIllegalPath("", "Attachment path must not be null or blank");
    }

    @Test
    public void validate_rejectsNewlines() {
        assertIllegalPath("/tmp/foo\nbar", "Attachment path must not contain newline characters");
        assertIllegalPath("/tmp/foo\rbar", "Attachment path must not contain newline characters");
    }

    @Test
    public void validate_rejectsRelativePath() {
        assertIllegalPath("log.txt", "Attachment path must be absolute");
        assertIllegalPath("files/log.txt", "Attachment path must be absolute");
    }

    private static void assertIllegalPath(String path, String message) {
        try {
            AttachmentPath.validate(path);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals(message, e.getMessage());
        }
    }
}
