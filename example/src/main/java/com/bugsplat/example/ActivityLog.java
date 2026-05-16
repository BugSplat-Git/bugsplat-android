package com.bugsplat.example;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class ActivityLog {

    public static final String TYPE_CRASH = "crash";
    public static final String TYPE_ERROR = "error";
    public static final String TYPE_FEEDBACK = "feedback";
    public static final String TYPE_HANG = "hang";

    private static final String TAG = "ActivityLog";
    private static final String PREFS = "bugsplat_example_activity";
    private static final String KEY_ENTRIES = "entries";
    private static final int MAX_ENTRIES = 3;

    private ActivityLog() {}

    public static final class Entry {
        public final String type;
        public final String detail;
        public final long timestampMs;

        Entry(String type, String detail, long timestampMs) {
            this.type = type;
            this.detail = detail;
            this.timestampMs = timestampMs;
        }
    }

    public static void record(Context context, String type, String detail) {
        List<Entry> entries = getAll(context);
        entries.add(0, new Entry(type, detail, System.currentTimeMillis()));
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }

        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("type", entry.type);
                obj.put("detail", entry.detail == null ? "" : entry.detail);
                obj.put("ts", entry.timestampMs);
                array.put(obj);
            } catch (JSONException e) {
                Log.w(TAG, "Failed to serialize entry", e);
            }
        }

        // commit (not apply) so crash entries survive an imminent crash.
        prefs(context).edit().putString(KEY_ENTRIES, array.toString()).commit();
    }

    public static List<Entry> getAll(Context context) {
        List<Entry> result = new ArrayList<>();
        String raw = prefs(context).getString(KEY_ENTRIES, null);
        if (raw == null) {
            return result;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                result.add(new Entry(
                        obj.optString("type"),
                        obj.optString("detail"),
                        obj.optLong("ts")));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Failed to parse stored entries", e);
        }
        return result;
    }

    public static void clear(Context context) {
        prefs(context).edit().remove(KEY_ENTRIES).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
