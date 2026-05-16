package com.bugsplat.example;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.bugsplat.android.BugSplat;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "BugSplatExample";

    private TextView statusTextView;
    private TextView sdkVersionTextView;
    private TextView connectedTextView;
    private LinearLayout recentActivityContainer;
    private TextView recentActivityEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusTextView = findViewById(R.id.statusTextView);
        sdkVersionTextView = findViewById(R.id.sdkVersionTextView);
        connectedTextView = findViewById(R.id.connectedTextView);
        recentActivityContainer = findViewById(R.id.recentActivityContainer);
        recentActivityEmpty = findViewById(R.id.recentActivityEmpty);

        sdkVersionTextView.setText(getString(R.string.demo_sdk_version_format, BuildConfig.BUGSPLAT_SDK_VERSION));
        ((TextView) findViewById(R.id.databaseBadgeTextView)).setText(BuildConfig.BUGSPLAT_DATABASE);

        bindCard(R.id.crashCard, R.drawable.splat_crash,
                R.string.card_crash_title, R.string.card_crash_subtitle, v -> triggerCrash());
        bindCard(R.id.errorCard, R.drawable.splat_error,
                R.string.card_error_title, R.string.card_error_subtitle, v -> triggerNonCrashError());
        bindCard(R.id.feedbackCard, R.drawable.splat_feedback,
                R.string.card_feedback_title, R.string.card_feedback_subtitle, v -> showFeedbackDialog());
        bindCard(R.id.hangCard, R.drawable.splat_hang,
                R.string.card_hang_title, R.string.card_hang_subtitle, v -> triggerHang());

        findViewById(R.id.viewDashboardTextView).setOnClickListener(v -> openDashboard());

        logNativeLibraryInfo();
        initializeBugSplat();
    }

    private void bindCard(int cardId, @DrawableRes int iconRes,
                          @StringRes int titleRes, @StringRes int subtitleRes,
                          View.OnClickListener onClick) {
        View card = findViewById(cardId);
        ((ImageView) card.findViewById(R.id.cardIcon)).setImageResource(iconRes);
        ((TextView) card.findViewById(R.id.cardTitle)).setText(titleRes);
        ((TextView) card.findViewById(R.id.cardSubtitle)).setText(subtitleRes);
        card.setOnClickListener(onClick);
    }

    private void setConnected(boolean connected) {
        connectedTextView.setText(connected ? R.string.demo_status_connected : R.string.demo_status_disconnected);
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderRecentActivity();
    }

    private void triggerCrash() {
        try {
            Log.d(TAG, "Triggering crash...");
            ActivityLog.record(this, ActivityLog.TYPE_CRASH, getString(R.string.activity_crash_detail));
            BugSplat.crash();
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Native method not found", e);
            statusTextView.setText("Error: Native method not found - " + e.getMessage());
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Error triggering crash", e);
            statusTextView.setText("Error: " + e.getMessage());
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void triggerHang() {
        Log.d(TAG, "Triggering ANR via native hang...");
        Toast.makeText(this, "Tap the screen to trigger the ANR dialog", Toast.LENGTH_SHORT).show();
        ActivityLog.record(this, ActivityLog.TYPE_HANG, getString(R.string.activity_hang_detail));
        // BugSplat.hang() blocks the main thread in a native infinite loop, producing
        // a symbolicated C++ frame in the resulting ANR dump.
        BugSplat.hang();
    }

    private void triggerNonCrashError() {
        try {
            String value = null;
            value.length();
        } catch (Exception e) {
            Log.e(TAG, "Caught non-crash exception", e);
            statusTextView.setText("Caught: " + e.getClass().getSimpleName() + " — app still running");
            Toast.makeText(this, "Exception caught", Toast.LENGTH_SHORT).show();
            ActivityLog.record(this, ActivityLog.TYPE_ERROR,
                    e.getClass().getSimpleName() + " caught");
            renderRecentActivity();
        }
    }

    private void showSetAttributeDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_attribute, null);
        EditText keyInput = dialogView.findViewById(R.id.attributeKey);
        EditText valueInput = dialogView.findViewById(R.id.attributeValue);

        new AlertDialog.Builder(this)
            .setTitle("Set Attribute")
            .setView(dialogView)
            .setPositiveButton("Set", (dialog, which) -> {
                String key = keyInput.getText().toString().trim();
                String value = valueInput.getText().toString().trim();

                if (key.isEmpty()) {
                    Toast.makeText(this, "Key is required", Toast.LENGTH_SHORT).show();
                    return;
                }

                BugSplat.setAttribute(key, value);
                statusTextView.setText("Attribute set: " + key + " = " + value);
                Toast.makeText(this, "Attribute set!", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "setAttribute: " + key + " = " + value);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showFeedbackDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_feedback, null);
        EditText titleInput = dialogView.findViewById(R.id.feedbackTitle);
        EditText descriptionInput = dialogView.findViewById(R.id.feedbackDescription);
        CheckBox includeLogsCheckbox = dialogView.findViewById(R.id.feedbackIncludeLogs);

        new AlertDialog.Builder(this)
            .setTitle("Send Feedback")
            .setView(dialogView)
            .setPositiveButton("Submit", (dialog, which) -> {
                String title = titleInput.getText().toString().trim();
                String description = descriptionInput.getText().toString().trim();
                boolean includeLogs = includeLogsCheckbox.isChecked();

                if (title.isEmpty()) {
                    Toast.makeText(this, "Subject is required", Toast.LENGTH_SHORT).show();
                    return;
                }

                statusTextView.setText("Sending feedback...");

                new Thread(() -> {
                    List<File> attachments = null;
                    if (includeLogs) {
                        File logFile = createSampleLogFile();
                        if (logFile != null) {
                            attachments = new ArrayList<>();
                            attachments.add(logFile);
                        }
                    }

                    boolean success = BugSplat.postFeedbackBlocking(
                        BuildConfig.BUGSPLAT_DATABASE,
                        BuildConfig.BUGSPLAT_APP_NAME,
                        BuildConfig.BUGSPLAT_APP_VERSION,
                        title,
                        description,
                        null,
                        null,
                        null,
                        attachments
                    );

                    runOnUiThread(() -> {
                        if (success) {
                            statusTextView.setText("Feedback sent — thank you!");
                            Toast.makeText(this, "Feedback sent!", Toast.LENGTH_SHORT).show();
                            String detail = title.isEmpty()
                                    ? getString(R.string.activity_feedback_detail_blank)
                                    : getString(R.string.activity_feedback_detail_format, title);
                            ActivityLog.record(this, ActivityLog.TYPE_FEEDBACK, detail);
                            renderRecentActivity();
                        } else {
                            statusTextView.setText("Failed to send feedback");
                            Toast.makeText(this, "Failed to send feedback", Toast.LENGTH_SHORT).show();
                        }
                    });
                }).start();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private File createSampleLogFile() {
        try {
            File logFile = new File(getCacheDir(), "sample_logs.txt");
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

    private void logNativeLibraryInfo() {
        try {
            File nativeLibDir = new File(getApplicationInfo().nativeLibraryDir);
            Log.d(TAG, "Native library directory: " + nativeLibDir.getAbsolutePath());

            if (nativeLibDir.exists() && nativeLibDir.isDirectory()) {
                File[] files = nativeLibDir.listFiles();
                if (files != null) {
                    Log.d(TAG, "Native libraries found: " + files.length);
                    for (File file : files) {
                        Log.d(TAG, "Native library: " + file.getName() + " (" + file.length() + " bytes)");
                    }
                } else {
                    Log.d(TAG, "No native libraries found or unable to list files");
                }
            } else {
                Log.d(TAG, "Native library directory does not exist or is not a directory");
            }

            checkLibrary(nativeLibDir, "libbugsplat.so");
            checkLibrary(nativeLibDir, "libcrashpad_handler.so");

            Log.d(TAG, "Library search path: " + System.getProperty("java.library.path"));
        } catch (Exception e) {
            Log.e(TAG, "Error logging native library info", e);
        }
    }

    private void checkLibrary(File directory, String libraryName) {
        File library = new File(directory, libraryName);
        if (library.exists()) {
            Log.d(TAG, "Library " + libraryName + " exists: " + library.getAbsolutePath() + " (" + library.length() + " bytes)");
        } else {
            Log.d(TAG, "Library " + libraryName + " does not exist in " + directory.getAbsolutePath());
        }
    }

    private void openDashboard() {
        Uri uri = Uri.parse("https://app.bugsplat.com/v2/dashboard")
                .buildUpon()
                .appendQueryParameter("database", BuildConfig.BUGSPLAT_DATABASE)
                .build();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No browser available to open " + uri, e);
            Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show();
        }
    }

    private void renderRecentActivity() {
        List<ActivityLog.Entry> entries = ActivityLog.getAll(this);
        recentActivityContainer.removeAllViews();

        if (entries.isEmpty()) {
            recentActivityEmpty.setVisibility(View.VISIBLE);
            recentActivityContainer.setVisibility(View.GONE);
            return;
        }

        recentActivityEmpty.setVisibility(View.GONE);
        recentActivityContainer.setVisibility(View.VISIBLE);

        LayoutInflater inflater = LayoutInflater.from(this);
        long now = System.currentTimeMillis();
        for (int i = 0; i < entries.size(); i++) {
            ActivityLog.Entry entry = entries.get(i);
            View row = inflater.inflate(R.layout.item_activity_row, recentActivityContainer, false);
            if (i > 0) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
                lp.topMargin = (int) (10 * getResources().getDisplayMetrics().density);
                row.setLayoutParams(lp);
            }
            row.findViewById(R.id.activityDot).setBackgroundResource(dotForType(entry.type));
            ((TextView) row.findViewById(R.id.activityLabel)).setText(labelForType(entry.type));
            ((TextView) row.findViewById(R.id.activityDetail)).setText(entry.detail);
            ((TextView) row.findViewById(R.id.activityTime)).setText(formatRelativeTime(now, entry.timestampMs));
            recentActivityContainer.addView(row);
        }
    }

    private int dotForType(String type) {
        switch (type) {
            case ActivityLog.TYPE_CRASH: return R.drawable.dot_activity_crash;
            case ActivityLog.TYPE_ERROR: return R.drawable.dot_activity_error;
            case ActivityLog.TYPE_FEEDBACK: return R.drawable.dot_activity_feedback;
            case ActivityLog.TYPE_HANG: return R.drawable.dot_activity_error;
            default: return R.drawable.dot_activity_error;
        }
    }

    private int labelForType(String type) {
        switch (type) {
            case ActivityLog.TYPE_CRASH: return R.string.activity_crash_label;
            case ActivityLog.TYPE_ERROR: return R.string.activity_error_label;
            case ActivityLog.TYPE_FEEDBACK: return R.string.activity_feedback_label;
            case ActivityLog.TYPE_HANG: return R.string.activity_hang_label;
            default: return R.string.activity_error_label;
        }
    }

    private String formatRelativeTime(long nowMs, long thenMs) {
        long deltaMs = Math.max(0, nowMs - thenMs);
        long minutes = deltaMs / 60_000L;
        if (minutes < 1) return getString(R.string.activity_time_just_now);
        if (minutes < 60) return getString(R.string.activity_time_minutes_ago, (int) minutes);
        long hours = minutes / 60;
        if (hours < 24) return getString(R.string.activity_time_hours_ago, (int) hours);
        long days = hours / 24;
        return getString(R.string.activity_time_days_ago, (int) days);
    }

    private void initializeBugSplat() {
        try {
            Log.d(TAG, "BugSplat Configuration:");
            Log.d(TAG, "  Database: " + BuildConfig.BUGSPLAT_DATABASE);
            Log.d(TAG, "  Application: " + BuildConfig.BUGSPLAT_APP_NAME);
            Log.d(TAG, "  Version: " + BuildConfig.BUGSPLAT_APP_VERSION);

            Log.d(TAG, "Initializing BugSplat...");
            BugSplat.init(this,
                          BuildConfig.BUGSPLAT_DATABASE,
                          BuildConfig.BUGSPLAT_APP_NAME,
                          BuildConfig.BUGSPLAT_APP_VERSION);

            setConnected(true);
            Log.d(TAG, "BugSplat initialized successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Native method not found", e);
            setConnected(false);
            statusTextView.setText("Status: Initialization failed - Native method not found");
            Toast.makeText(this, "Failed to initialize BugSplat: Native method not found", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize BugSplat", e);
            setConnected(false);
            statusTextView.setText("Status: Initialization failed - " + e.getMessage());
            Toast.makeText(this, "Failed to initialize BugSplat: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
