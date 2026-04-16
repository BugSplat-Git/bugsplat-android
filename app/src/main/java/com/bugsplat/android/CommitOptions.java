package com.bugsplat.android;

import org.json.JSONObject;

import java.util.Map;

/**
 * Typed, optional fields for the {@code commitS3CrashUpload} request.
 *
 * Mirrors the documented BugSplat commit API 1-to-1:
 * https://docs.bugsplat.com/introduction/development/web-services/crash#request-body-multipart-form-data
 *
 * All fields are optional. Use the fluent setters to populate only the fields
 * you need. {@code appName}, {@code appVersion}, {@code s3Key}, {@code md5},
 * and {@code database} are set by {@link ReportUploader} from constructor
 * state and the upload flow, so they are not present here.
 */
class CommitOptions {
    String crashType;
    Integer crashTypeId;
    Integer fullDumpFlag;
    String appKey;
    String description;
    String user;
    String email;
    String internalIP;
    String notes;
    String processor;
    String crashTime;
    Map<String, String> attributes;
    String crashHash;

    CommitOptions() {}

    CommitOptions crashType(String v) { this.crashType = v; return this; }
    CommitOptions crashTypeId(int v) { this.crashTypeId = v; return this; }
    CommitOptions fullDumpFlag(int v) { this.fullDumpFlag = v; return this; }
    CommitOptions appKey(String v) { this.appKey = v; return this; }
    CommitOptions description(String v) { this.description = v; return this; }
    CommitOptions user(String v) { this.user = v; return this; }
    CommitOptions email(String v) { this.email = v; return this; }
    CommitOptions internalIP(String v) { this.internalIP = v; return this; }
    CommitOptions notes(String v) { this.notes = v; return this; }
    CommitOptions processor(String v) { this.processor = v; return this; }
    CommitOptions crashTime(String v) { this.crashTime = v; return this; }
    CommitOptions attributes(Map<String, String> v) { this.attributes = v; return this; }
    CommitOptions crashHash(String v) { this.crashHash = v; return this; }

    /**
     * Return the attributes as a JSON string (the format the commit endpoint
     * expects), or {@code null} if no attributes are set.
     */
    String attributesJson() {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        return new JSONObject(attributes).toString();
    }
}
