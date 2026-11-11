package com.bugsplat.android;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java-side mirror of the values handed to {@code init}.
 *
 * <p>Crashpad owns this state natively, but native memory is not readable from
 * Java, so APIs that build a report in-process — {@link ExceptionReporter} —
 * need their own copy of the database/application/version triple plus the
 * attributes and attachments that a crash report would carry.</p>
 *
 * <p>All accessors are safe to call from any thread.</p>
 */
final class BugSplatConfig {
    private static volatile String database;
    private static volatile String application;
    private static volatile String version;
    private static final Map<String, String> attributes = new ConcurrentHashMap<>();
    private static volatile List<String> attachments = Collections.emptyList();

    private BugSplatConfig() {
    }

    /** Record the init parameters. Replaces any state from a prior init. */
    static void init(String database, String application, String version,
                     Map<String, String> attributes, String[] attachments) {
        BugSplatConfig.database = database;
        BugSplatConfig.application = application;
        BugSplatConfig.version = version;

        BugSplatConfig.attributes.clear();
        if (attributes != null) {
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    BugSplatConfig.attributes.put(entry.getKey(), entry.getValue());
                }
            }
        }

        BugSplatConfig.attachments = attachments == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(java.util.Arrays.asList(attachments)));
    }

    /** True once {@code init} has supplied the database/application/version triple. */
    static boolean isInitialized() {
        return database != null && application != null && version != null;
    }

    static String database() {
        return database;
    }

    static String application() {
        return application;
    }

    static String version() {
        return version;
    }

    static void setAttribute(String key, String value) {
        attributes.put(key, value);
    }

    static void removeAttribute(String key) {
        attributes.remove(key);
    }

    /** A point-in-time copy of the attributes, in no particular order. */
    static Map<String, String> attributesSnapshot() {
        return new LinkedHashMap<>(attributes);
    }

    /** The init attachments as {@link File}s; missing paths are left for the zip builder to skip. */
    static List<File> attachmentFiles() {
        List<String> paths = attachments;
        List<File> files = new ArrayList<>(paths.size());
        for (String path : paths) {
            if (path != null && !path.isEmpty()) {
                files.add(new File(path));
            }
        }
        return files;
    }

    /** Test hook — clears all recorded state. */
    static void reset() {
        database = null;
        application = null;
        version = null;
        attributes.clear();
        attachments = Collections.emptyList();
    }
}
