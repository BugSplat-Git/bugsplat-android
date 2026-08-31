package com.bugsplat.android;

final class AttachmentPath {
    private AttachmentPath() {
    }

    static void validate(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Attachment path must not be null or blank");
        }
        if (path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Attachment path must not contain newline characters");
        }
    }
}
