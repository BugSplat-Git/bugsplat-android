package com.bugsplat.android;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Utility class for uploading debug symbols for native libraries (.so files)
 * to the BugSplat service.
 */
public class SymbolUploader {
    private static final String TAG = "BugSplat-SymbolUploader";
    private static final String SYMBOL_UPLOAD_BINARY = "symbol-upload";
    
    private final String database;
    private final String application;
    private final String version;
    private final String clientId;
    private final String clientSecret;
    private final ExecutorService executor;
    
    /**
     * Creates a new SymbolUploader instance.
     * 
     * @param database The BugSplat database name
     * @param application The application name
     * @param version The application version
     */
    public SymbolUploader(String database, String application, String version) {
        this(database, application, version, null, null);
    }
    
    /**
     * Creates a new SymbolUploader instance with client credentials.
     * 
     * @param database The BugSplat database name
     * @param application The application name
     * @param version The application version
     * @param clientId The BugSplat API client ID (optional)
     * @param clientSecret The BugSplat API client secret (optional)
     */
    public SymbolUploader(String database, String application, String version, String clientId, String clientSecret) {
        this.database = database;
        this.application = application;
        this.version = version;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.executor = Executors.newSingleThreadExecutor();
    }
    
    /**
     * Uploads debug symbols for all .so files in the specified directory.
     * This method runs asynchronously and returns immediately.
     * 
     * @param context The application context
     * @param nativeLibsDir The directory containing the native libraries
     */
    public void uploadSymbols(Context context, String nativeLibsDir) {
        executor.execute(() -> {
            try {
                uploadSymbolsInternal(context, nativeLibsDir);
            } catch (Exception e) {
                Log.e(TAG, "Failed to upload symbols", e);
            }
        });
    }
    
    /**
     * Uploads debug symbols for all .so files in the specified directory.
     * This method blocks until the upload is complete.
     * 
     * @param context The application context
     * @param nativeLibsDir The directory containing the native libraries
     * @throws IOException If an I/O error occurs
     */
    public void uploadSymbolsBlocking(Context context, String nativeLibsDir) throws IOException {
        uploadSymbolsInternal(context, nativeLibsDir);
    }
    
    private void uploadSymbolsInternal(Context context, String nativeLibsDir) throws IOException {
        File symbolUploadBinary = extractSymbolUploadBinary(context);
        if (symbolUploadBinary == null) {
            Log.e(TAG, "Failed to extract symbol-upload binary");
            return;
        }
        
        // Make the binary executable
        if (!symbolUploadBinary.setExecutable(true)) {
            Log.e(TAG, "Failed to make symbol-upload binary executable");
            return;
        }
        
        File libDir = new File(nativeLibsDir);
        if (!libDir.exists() || !libDir.isDirectory()) {
            Log.e(TAG, "Native library directory does not exist: " + nativeLibsDir);
            return;
        }
        
        // Build the command with the directory and glob pattern
        List<String> command = new ArrayList<>();
        command.add(symbolUploadBinary.getAbsolutePath());
        command.add("-b");
        command.add(database);
        command.add("-a");
        command.add(application);
        command.add("-v");
        command.add(version);
        
        // Add client credentials if provided
        if (clientId != null && !clientId.isEmpty() && clientSecret != null && !clientSecret.isEmpty()) {
            command.add("-i");
            command.add(clientId);
            command.add("-s");
            command.add(clientSecret);
        }
        
        // Add directory and file pattern
        command.add("-d");
        command.add(libDir.getAbsolutePath());
        command.add("-f");
        command.add("**/*.so");
        command.add("-m"); // Enable multi-threading
        
        // Execute the command
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        
        // Read the output
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        StringBuilder output = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            output.append(line).append("\n");
            Log.d(TAG, line); // Log each line of output
        }
        
        try {
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                Log.i(TAG, "Successfully uploaded symbols from " + nativeLibsDir);
            } else {
                Log.e(TAG, "Failed to upload symbols, exit code: " + exitCode + ", output: " + output);
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Symbol upload process was interrupted", e);
            Thread.currentThread().interrupt();
        }
    }
    
    private File extractSymbolUploadBinary(Context context) {
        try {
            // Extract the symbol-upload binary to the app's cache directory
            File binaryFile = new File(context.getCacheDir(), SYMBOL_UPLOAD_BINARY);
            
            // Check if we already extracted it
            if (binaryFile.exists()) {
                return binaryFile;
            }
            
            // Extract the binary from assets
            java.io.InputStream inputStream = context.getAssets().open(SYMBOL_UPLOAD_BINARY);
            java.io.FileOutputStream outputStream = new java.io.FileOutputStream(binaryFile);
            byte[] buffer = new byte[1024];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            inputStream.close();
            outputStream.close();
            
            return binaryFile;
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract symbol-upload binary", e);
            return null;
        }
    }
    
    /**
     * Shuts down the executor service.
     * Call this method when you're done with the SymbolUploader.
     */
    public void shutdown() {
        executor.shutdown();
    }
} 