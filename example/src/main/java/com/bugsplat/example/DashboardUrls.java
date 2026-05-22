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
     * A direct link to a specific report by id, e.g.
     * {@code .../v2/crash?database=demo&id=7733}.
     *
     * <p>This is preferred over the SDK's {@code infoUrl} for feedback: feedback
     * reports group by their (unique) title, so {@code infoUrl} resolves to a
     * generic page rather than the individual report.</p>
     *
     * <p>Falls back to the database dashboard when the id is unavailable.</p>
     */
    static Uri forReport(String database, Integer crashId) {
        if (crashId == null) {
            return forDatabase(database);
        }
        return Uri.parse("https://app.bugsplat.com/v2/crash")
                .buildUpon()
                .appendQueryParameter("database", database)
                .appendQueryParameter("id", String.valueOf(crashId))
                .build();
    }
}
