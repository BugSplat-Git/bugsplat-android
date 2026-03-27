package com.bugsplat.example;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

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
    private Button crashButton;
    private Button feedbackButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize views
        statusTextView = findViewById(R.id.statusTextView);
        crashButton = findViewById(R.id.crashButton);
        feedbackButton = findViewById(R.id.feedbackButton);

        // Log native library directories
        logNativeLibraryInfo();

        // Initialize BugSplat at app start
        initializeBugSplat();

        // Set up click listener for crash button
        crashButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    // Cause a crash using BugSplat's crash method
                    Log.d(TAG, "Triggering crash...");
                    BugSplat.crash();
                } catch (UnsatisfiedLinkError e) {
                    Log.e(TAG, "Native method not found", e);
                    statusTextView.setText("Error: Native method not found - " + e.getMessage());
                    Toast.makeText(MainActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Log.e(TAG, "Error triggering crash", e);
                    statusTextView.setText("Error: " + e.getMessage());
                    Toast.makeText(MainActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });

        // Set up click listener for feedback button
        feedbackButton.setOnClickListener(v -> showFeedbackDialog());
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
            // Log application native library directory
            File nativeLibDir = new File(getApplicationInfo().nativeLibraryDir);
            Log.d(TAG, "Native library directory: " + nativeLibDir.getAbsolutePath());

            // List all files in the native library directory
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

            // Check for specific libraries
            checkLibrary(nativeLibDir, "libbugsplat.so");
            checkLibrary(nativeLibDir, "libcrashpad_handler.so");

            // Log library search path
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

    private void initializeBugSplat() {
        try {
            // Log BugSplat configuration from BuildConfig
            Log.d(TAG, "BugSplat Configuration:");
            Log.d(TAG, "  Database: " + BuildConfig.BUGSPLAT_DATABASE);
            Log.d(TAG, "  Application: " + BuildConfig.BUGSPLAT_APP_NAME);
            Log.d(TAG, "  Version: " + BuildConfig.BUGSPLAT_APP_VERSION);

            Log.d(TAG, "Initializing BugSplat...");
            // Initialize BugSplat with values from BuildConfig
            BugSplat.init(this,
                          BuildConfig.BUGSPLAT_DATABASE,
                          BuildConfig.BUGSPLAT_APP_NAME,
                          BuildConfig.BUGSPLAT_APP_VERSION);

            // Update UI
            String statusText = String.format("Status: BugSplat initialized\nDatabase: %s\nApp: %s\nVersion: %s",
                                             BuildConfig.BUGSPLAT_DATABASE,
                                             BuildConfig.BUGSPLAT_APP_NAME,
                                             BuildConfig.BUGSPLAT_APP_VERSION);
            statusTextView.setText(statusText);
            Toast.makeText(this, "BugSplat initialized successfully", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "BugSplat initialized successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Native method not found", e);
            statusTextView.setText("Status: Initialization failed - Native method not found");
            Toast.makeText(this, "Failed to initialize BugSplat: Native method not found", Toast.LENGTH_LONG).show();
            e.printStackTrace();
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize BugSplat", e);
            statusTextView.setText("Status: Initialization failed - " + e.getMessage());
            Toast.makeText(this, "Failed to initialize BugSplat: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }
}
