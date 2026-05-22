package com.bugsplat.example;

import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Helpers for the files attached to a User Feedback report: the rolling sample
 * log file, and copying a picked content {@link Uri} into the app cache.
 */
final class FeedbackAttachments {

    private static final String TAG = "BugSplatExample";

    private FeedbackAttachments() {}

    /** A file copied into the app cache, ready to attach to a feedback report. */
    static final class Picked {
        final File file;
        final String displayName;
        final long sizeBytes;
        /** "W × H" for images, or null when the file is not an image. */
        final String dimensions;

        Picked(File file, String displayName, long sizeBytes, String dimensions) {
            this.file = file;
            this.displayName = displayName;
            this.sizeBytes = sizeBytes;
            this.dimensions = dimensions;
        }

        /** Human-readable meta line, e.g. "1170 × 2532  ·  287 KB". */
        String metaLine(Context context) {
            String size = Formatter.formatShortFileSize(context, sizeBytes);
            return dimensions != null ? dimensions + "  ·  " + size : size;
        }
    }

    /** Build the rolling sample log file used by the "Include app logs" option. */
    static File createSampleLogFile(Context context) {
        try {
            File logFile = new File(context.getCacheDir(), "sample_logs.txt");
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            try (FileWriter writer = new FileWriter(logFile)) {
                writer.write("=== BugSplat Sample Log File ===\n");
                writer.write("Generated: " + timestamp + "\n\n");
                writer.write("[INFO]  " + timestamp + " Application started\n");
                writer.write("[DEBUG] " + timestamp + " BugSplat SDK initialized\n");
                writer.write("[INFO]  " + timestamp + " User navigated to main screen\n");
                writer.write("[WARN]  " + timestamp + " Network latency detected (250ms)\n");
                writer.write("[DEBUG] " + timestamp + " Cache cleared successfully\n");
                writer.write("[INFO]  " + timestamp + " User submitted feedback\n");
            }
            return logFile;
        } catch (IOException e) {
            Log.e(TAG, "Failed to create sample log file", e);
            return null;
        }
    }

    /**
     * Copy a content {@link Uri} from the system file picker into the app cache.
     * {@code FeedbackClient} zips real {@link File} objects and the picker Uri is
     * only valid transiently, so the bytes must be copied before submission.
     *
     * @return the copied file with its metadata, or null if the copy failed
     */
    static Picked copyUriToCache(Context context, Uri uri) {
        String displayName = "attachment";
        long size = 0;
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                    displayName = cursor.getString(nameIdx);
                }
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                    size = cursor.getLong(sizeIdx);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not query attachment metadata", e);
        }

        // Sanitize so the name is safe as both a cache filename and a zip entry.
        String safeName = displayName.replaceAll("[/\\\\]", "_");
        File dest = new File(context.getCacheDir(), "attachment_" + safeName);
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) {
                Log.e(TAG, "Could not open attachment input stream");
                return null;
            }
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy attachment to cache", e);
            return null;
        }

        if (size <= 0) {
            size = dest.length();
        }
        return new Picked(dest, safeName, size, imageDimensions(dest));
    }

    /** Return "W × H" for image files, or null when the file is not a decodable image. */
    private static String imageDimensions(File file) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                return opts.outWidth + " × " + opts.outHeight;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read image dimensions", e);
        }
        return null;
    }
}
