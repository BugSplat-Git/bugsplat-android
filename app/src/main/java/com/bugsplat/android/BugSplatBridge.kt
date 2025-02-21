package com.bugsplat.android

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.util.Log

object BugSplatBridge {
    init {
        System.loadLibrary("native-lib")
    }

    fun initBugSplat(
        activity: Activity,
        database: String?,
        application: String?,
        version: String?
    ) {
        val applicationInfo: ApplicationInfo = activity.getApplicationInfo()
        Log.d(
            "BugSplat", "init result: " +
                    jniInitBugSplat(
                        applicationInfo.dataDir,
                        applicationInfo.nativeLibraryDir,
                        database,
                        application,
                        version
                    )
        )
    }

    fun crash() {
        jniCrash()
    }

    external fun jniInitBugSplat(
        dataDir: String?,
        libDir: String?,
        database: String?,
        application: String?,
        version: String?
    ): Boolean

    external fun jniCrash()
}