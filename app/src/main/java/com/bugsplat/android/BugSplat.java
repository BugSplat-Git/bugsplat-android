package com.bugsplat.android;

import android.app.Activity;
import android.content.Context;
import java.util.Map;

/**
 * Main entry point for the BugSplat Android SDK.
 * This class provides a simplified interface to the BugSplatBridge.
 */
public class BugSplat {
    
    /**
     * Initialize BugSplat with the given parameters.
     *
     * @param activity The current activity
     * @param database The BugSplat database name
     * @param application The application name
     * @param version The application version
     */
    public static void init(Activity activity, String database, String application, String version) {
        BugSplatBridge.initBugSplat(activity, database, application, version);
    }
    
    /**
     * Initialize BugSplat with the given parameters and attachments.
     *
     * @param activity The current activity
     * @param database The BugSplat database name
     * @param application The application name
     * @param version The application version
     * @param attachments Array of file paths to attach to crash reports
     */
    public static void init(Activity activity, String database, String application, String version, String[] attachments) {
        BugSplatBridge.initBugSplat(activity, database, application, version, attachments);
    }
    
    /**
     * Initialize BugSplat with the given parameters and custom attributes.
     *
     * @param activity The current activity
     * @param database The BugSplat database name
     * @param application The application name
     * @param version The application version
     * @param attributes Map of custom key-value pairs to include with crash reports
     */
    public static void init(Activity activity, String database, String application, String version, Map<String, String> attributes) {
        BugSplatBridge.initBugSplat(activity, database, application, version, attributes);
    }
    
    /**
     * Initialize BugSplat with the given parameters, custom attributes, and attachments.
     *
     * @param activity The current activity
     * @param database The BugSplat database name
     * @param application The application name
     * @param version The application version
     * @param attributes Map of custom key-value pairs to include with crash reports
     * @param attachments Array of file paths to attach to crash reports
     */
    public static void init(Activity activity, String database, String application, String version, Map<String, String> attributes, String[] attachments) {
        BugSplatBridge.initBugSplat(activity, database, application, version, attributes, attachments);
    }
    
    /**
     * Manually trigger a crash for testing purposes.
     */
    public static void crash() {
        BugSplatBridge.crash();
    }
    
    /**
     * Upload debug symbols for native libraries (.so files) in the specified directory.
     * This method runs asynchronously and returns immediately.
     *
     * @param context The application context
     * @param database The BugSplat database name
     * @param application The application name
     * @param version The application version
     * @param nativeLibsDir The directory containing the native libraries
     */
    public static void uploadSymbols(Context context, String database, String application, String version, String nativeLibsDir) {
        SymbolUploader uploader = new SymbolUploader(database, application, version);
        uploader.uploadSymbols(context, nativeLibsDir);
    }
    
    /**
     * Upload debug symbols for native libraries (.so files) in the specified directory.
     * This method runs asynchronously and returns immediately.
     *
     * @param context The application context
     * @param database The BugSplat database name
     * @param application The application name
     * @param version The application version
     * @param clientId The BugSplat API client ID
     * @param clientSecret The BugSplat API client secret
     * @param nativeLibsDir The directory containing the native libraries
     */
    public static void uploadSymbols(Context context, String database, String application, String version, 
                                    String clientId, String clientSecret, String nativeLibsDir) {
        SymbolUploader uploader = new SymbolUploader(database, application, version, clientId, clientSecret);
        uploader.uploadSymbols(context, nativeLibsDir);
    }
    
    /**
     * Upload debug symbols for native libraries (.so files) in the specified directory.
     * This method blocks until the upload is complete.
     *
     * @param context The application context
     * @param database The BugSplat database name
     * @param application The application name
     * @param version The application version
     * @param nativeLibsDir The directory containing the native libraries
     * @throws java.io.IOException If an I/O error occurs
     */
    public static void uploadSymbolsBlocking(Context context, String database, String application, String version, String nativeLibsDir) throws java.io.IOException {
        SymbolUploader uploader = new SymbolUploader(database, application, version);
        uploader.uploadSymbolsBlocking(context, nativeLibsDir);
    }
    
    /**
     * Upload debug symbols for native libraries (.so files) in the specified directory.
     * This method blocks until the upload is complete.
     *
     * @param context The application context
     * @param database The BugSplat database name
     * @param application The application name
     * @param version The application version
     * @param clientId The BugSplat API client ID
     * @param clientSecret The BugSplat API client secret
     * @param nativeLibsDir The directory containing the native libraries
     * @throws java.io.IOException If an I/O error occurs
     */
    public static void uploadSymbolsBlocking(Context context, String database, String application, String version,
                                           String clientId, String clientSecret, String nativeLibsDir) throws java.io.IOException {
        SymbolUploader uploader = new SymbolUploader(database, application, version, clientId, clientSecret);
        uploader.uploadSymbolsBlocking(context, nativeLibsDir);
    }
} 