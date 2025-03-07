package com.bugsplat.example;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bugsplat.android.BugSplat;

import java.io.File;
import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "BugSplatExample";
    private TextView statusTextView;
    private Button crashButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize views
        statusTextView = findViewById(R.id.statusTextView);
        crashButton = findViewById(R.id.crashButton);

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
            Log.d(TAG, "Initializing BugSplat...");
            // Initialize BugSplat with your database, application, and version
            BugSplat.init(this, "fred", "my-android-crasher", "1.0.0");
            
            // Update UI
            statusTextView.setText("Status: BugSplat initialized");
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