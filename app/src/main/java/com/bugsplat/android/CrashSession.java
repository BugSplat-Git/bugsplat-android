package com.bugsplat.android;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The report metadata of one process: database, application, version, attributes and
 * attachment paths. Used on Android 12+, where a native crash is reported from its tombstone
 * by the next process, so the values must outlive the process that crashed.
 *
 * <p>Each process writes its own file, named by PID, and rewrites it whenever a value
 * changes. A new session therefore never overwrites the values of the session that crashed.</p>
 */
final class CrashSession {
    private static final String TAG = "BugSplat-Tombstone";

    final String database;
    final String application;
    final String version;
    private final Map<String, String> attributes = new LinkedHashMap<>();
    private final Set<String> attachments = new LinkedHashSet<>();
    private final File file;

    CrashSession(File file, String database, String application, String version) {
        this.file = file;
        this.database = database;
        this.application = application;
        this.version = version;
    }

    /** Creates and persists the session of the current process. */
    static CrashSession start(Context context, String database, String application, String version,
                              Map<String, String> attributes, String[] attachments) {
        CrashSession session = new CrashSession(fileFor(directory(context), Process.myPid()),
                database, application, version);

        if (attributes != null) {
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    session.attributes.put(entry.getKey(), entry.getValue());
                }
            }
        }

        if (attachments != null) {
            for (String path : attachments) {
                try {
                    AttachmentPath.validate(path);
                    session.attachments.add(path);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Skipping invalid attachment path: " + path);
                }
            }
        }

        session.save();
        return session;
    }

    static File directory(Context context) {
        return new File(context.getNoBackupFilesDir(), "bugsplat/sessions");
    }

    static File fileFor(File directory, int pid) {
        return new File(directory, pid + ".json");
    }

    /**
     * The session persisted by process {@code pid}, or null when there is none. A file
     * written after {@code exitTimestamp} belongs to a later process that reused the PID.
     */
    static CrashSession load(File directory, int pid, long exitTimestamp) {
        File file = fileFor(directory, pid);
        if (!file.isFile() || file.lastModified() > exitTimestamp) {
            return null;
        }

        try {
            JSONObject json = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            CrashSession session = new CrashSession(file, json.getString("database"),
                    json.getString("application"), json.getString("version"));

            JSONObject attributes = json.getJSONObject("attributes");
            Iterator<String> keys = attributes.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                session.attributes.put(key, attributes.getString(key));
            }

            JSONArray attachments = json.getJSONArray("attachments");
            for (int i = 0; i < attachments.length(); i++) {
                session.attachments.add(attachments.getString(i));
            }

            return session;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Could not read session " + file, e);
            return null;
        }
    }

    /** A null value removes the attribute, matching {@link BugSplatBridge#setAttribute}. */
    synchronized void setAttribute(String key, String value) {
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
        save();
    }

    synchronized void addAttachment(String path) {
        if (attachments.add(path)) {
            save();
        }
    }

    synchronized void removeAttachment(String path) {
        if (attachments.remove(path)) {
            save();
        }
    }

    synchronized Map<String, String> attributes() {
        return new LinkedHashMap<>(attributes);
    }

    synchronized List<String> attachments() {
        return new ArrayList<>(attachments);
    }

    /** Written synchronously, so a value set just before a crash is not lost. */
    private void save() {
        try {
            JSONObject json = new JSONObject();
            json.put("database", database);
            json.put("application", application);
            json.put("version", version);
            json.put("attributes", new JSONObject(attributes));
            json.put("attachments", new JSONArray(attachments));

            File directory = file.getParentFile();
            if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Could not create " + directory);
            }

            File tmp = new File(file.getPath() + ".tmp");
            Files.write(tmp.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(file)) {
                throw new IOException("Could not replace " + file);
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Could not persist session " + file, e);
        }
    }
}
