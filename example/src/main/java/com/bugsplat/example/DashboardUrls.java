package com.bugsplat.example;

import android.net.Uri;

/**
 * Builds BugSplat web-app URLs for the demo.
 */
final class DashboardUrls {

    private DashboardUrls() {}

    /** The dashboard for a database, e.g. {@code .../v2/dashboard?database=demo}. */
    static Uri forDatabase(String database) {
        return Uri.parse("https://app.bugsplat.com/v2/dashboard")
                .buildUpon()
                .appendQueryParameter("database", database)
                .build();
    }

    /**
     * A link to a specific report. Prefers the {@code infoUrl} returned by the
     * SDK; falls back to the database dashboard when it is unavailable.
     */
    static Uri forReport(String infoUrl, String database) {
        if (infoUrl != null && !infoUrl.isEmpty()) {
            return Uri.parse(infoUrl);
        }
        return forDatabase(database);
    }
}
