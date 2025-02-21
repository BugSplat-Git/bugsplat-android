#include <jni.h>
#include <string>
#include <unistd.h>
#include "client/crashpad_client.h"
#include "client/crash_report_database.h"
#include "client/settings.h"

using namespace base;
using namespace crashpad;
using namespace std;

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_bugsplat_android_BugSplatBridge_jniInitBugSplat(JNIEnv *env, jclass clazz,
                                                                       jstring data_dir,
                                                                       jstring lib_dir,
                                                                       jstring database,
                                                                       jstring application,
                                                                       jstring version) {

    string dataDir = env->GetStringUTFChars(data_dir, nullptr);
    string libDir = env->GetStringUTFChars(lib_dir, nullptr);

    // Crashpad file paths
    FilePath handler(libDir + "/libcrashpad_handler.so");
    FilePath reportsDir(dataDir + "/crashpad");
    FilePath metricsDir(dataDir + "/crashpad");

    string databaseString = env->GetStringUTFChars(database, nullptr);

    // Crashpad upload URL for BugSplat database
    string url = "https://" + databaseString + ".bugsplat.com/post/bp/crash/crashpad.php";

    // Crashpad annotations
    map<string, string> annotations;
    annotations["format"] = "minidump";
    annotations["database"] = databaseString;
    annotations["product"] = env->GetStringUTFChars(application, nullptr);
    annotations["version"] = env->GetStringUTFChars(version, nullptr);

    // Crashpad arguments
    vector<string> arguments;
    arguments.emplace_back("--no-rate-limit");

    // Crashpad local database
    unique_ptr<CrashReportDatabase> crashReportDatabase = CrashReportDatabase::Initialize(
            reportsDir);
    if (crashReportDatabase == nullptr)
        return false;

    // Enable automated crash uploads
    Settings *settings = crashReportDatabase->GetSettings();
    if (settings == nullptr)
        return false;
    settings->SetUploadsEnabled(true);

    // File paths of attachments to be uploaded with the minidump file at crash time - default bundle limit is 20MB
    vector<FilePath> attachments;
    // Start Crashpad crash handler
    static auto *client = new CrashpadClient();
    return client->StartHandlerAtCrash(handler, reportsDir, metricsDir, url, annotations,
                                arguments, attachments);
}
extern "C"
JNIEXPORT void JNICALL
Java_com_bugsplat_android_BugSplatBridge_jniCrash(JNIEnv *env, jclass clazz) {
    *(volatile int *) nullptr = 0;
}