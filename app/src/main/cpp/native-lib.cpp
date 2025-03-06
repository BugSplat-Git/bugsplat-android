#include <android/log.h>
#include <jni.h>
#include <string>
#include <unistd.h>
#include "client/crashpad_client.h"
#include "client/crash_report_database.h"
#include "client/settings.h"
#include "include/bugsplat_utils.h"

using namespace base;
using namespace crashpad;
using namespace std;

// Forward declarations of JNI functions
extern "C" JNIEXPORT jboolean JNICALL
Java_com_bugsplat_android_BugSplatBridge_jniInitBugSplat(JNIEnv *env, jclass clazz,
                                                         jstring data_dir,
                                                         jstring lib_dir,
                                                         jstring database,
                                                         jstring application,
                                                         jstring version,
                                                         jobject attributes_map,
                                                         jobjectArray attachments);

extern "C" JNIEXPORT void JNICALL
Java_com_bugsplat_android_BugSplatBridge_jniCrash(JNIEnv *env, jclass clazz);

// JNI implementation
extern "C" JNIEXPORT jboolean JNICALL
Java_com_bugsplat_android_BugSplatBridge_jniInitBugSplat(JNIEnv *env, jclass clazz,
                                                         jstring data_dir,
                                                         jstring lib_dir,
                                                         jstring database,
                                                         jstring application,
                                                         jstring version,
                                                         jobject attributes_map,
                                                         jobjectArray attachments)
{
    string dataDir = env->GetStringUTFChars(data_dir, nullptr);
    string libDir = env->GetStringUTFChars(lib_dir, nullptr);

    // Crashpad file paths
    FilePath handler(libDir + "/libcrashpad_handler.so");
    FilePath reportsDir(dataDir + "/crashpad");
    FilePath metricsDir(dataDir + "/crashpad");

    string databaseString = env->GetStringUTFChars(database, nullptr);

    // Crashpad upload URL for BugSplat database
    string url = "https://" + databaseString + ".bugsplat.com/post/bp/crash/crashpad.php";
    __android_log_print(ANDROID_LOG_INFO, "bugsplat-android", "Url: %s", url.c_str());

    // Crashpad annotations
    map<string, string> annotations;
    annotations["format"] = "minidump";
    annotations["database"] = databaseString;
    annotations["product"] = env->GetStringUTFChars(application, nullptr);
    annotations["version"] = env->GetStringUTFChars(version, nullptr);

    // Create custom attributes
    createAttributes(env, attributes_map, annotations);

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

    // Create attachments
    vector<FilePath> attachmentPaths = createAttachments(env, attachments);

    // Start Crashpad crash handler
    static auto *client = new CrashpadClient();
    bool result = client->StartHandlerAtCrash(handler, reportsDir, metricsDir, url, annotations,
                                arguments, attachmentPaths);

    __android_log_print(ANDROID_LOG_INFO, "bugsplat-android", "StartHandlerAtCrash result: %s", result ? "success" : "fail");

    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bugsplat_android_BugSplatBridge_jniCrash(JNIEnv *env, jclass clazz)
{
    volatile int* a = reinterpret_cast<volatile int*>(0x42);
    *a = 1;
}

// Utility function implementations
void createAttributes(JNIEnv *env, jobject attributes_map, map<string, string>& annotations) {
    if (attributes_map == nullptr) {
        return;
    }
    
    // Get Map class and methods
    jclass mapClass = env->FindClass("java/util/Map");
    jmethodID entrySetMethod = env->GetMethodID(mapClass, "entrySet", "()Ljava/util/Set;");
    
    // Get Set of Map.Entry objects
    jobject entrySet = env->CallObjectMethod(attributes_map, entrySetMethod);
    jclass setClass = env->FindClass("java/util/Set");
    jmethodID iteratorMethod = env->GetMethodID(setClass, "iterator", "()Ljava/util/Iterator;");
    
    // Get Iterator
    jobject iterator = env->CallObjectMethod(entrySet, iteratorMethod);
    jclass iteratorClass = env->FindClass("java/util/Iterator");
    jmethodID hasNextMethod = env->GetMethodID(iteratorClass, "hasNext", "()Z");
    jmethodID nextMethod = env->GetMethodID(iteratorClass, "next", "()Ljava/lang/Object;");
    
    // Get Map.Entry class and methods
    jclass entryClass = env->FindClass("java/util/Map$Entry");
    jmethodID getKeyMethod = env->GetMethodID(entryClass, "getKey", "()Ljava/lang/Object;");
    jmethodID getValueMethod = env->GetMethodID(entryClass, "getValue", "()Ljava/lang/Object;");
    
    // Iterate through entries
    while (env->CallBooleanMethod(iterator, hasNextMethod)) {
        jobject entry = env->CallObjectMethod(iterator, nextMethod);
        jstring key = (jstring)env->CallObjectMethod(entry, getKeyMethod);
        jstring value = (jstring)env->CallObjectMethod(entry, getValueMethod);
        
        const char* keyStr = env->GetStringUTFChars(key, nullptr);
        const char* valueStr = env->GetStringUTFChars(value, nullptr);
        
        // Add to annotations
        annotations[keyStr] = valueStr;
        
        // Release resources
        env->ReleaseStringUTFChars(key, keyStr);
        env->ReleaseStringUTFChars(value, valueStr);
        env->DeleteLocalRef(key);
        env->DeleteLocalRef(value);
        env->DeleteLocalRef(entry);
    }
    
    // Clean up references
    env->DeleteLocalRef(iterator);
    env->DeleteLocalRef(entrySet);
}

vector<FilePath> createAttachments(JNIEnv *env, jobjectArray attachments) {
    vector<FilePath> attachmentPaths;
    
    if (attachments == nullptr) {
        return attachmentPaths;
    }
    
    jsize length = env->GetArrayLength(attachments);
    for (jsize i = 0; i < length; i++) {
        jstring path = (jstring)env->GetObjectArrayElement(attachments, i);
        const char* pathStr = env->GetStringUTFChars(path, nullptr);
        
        // Log the attachment path for debugging
        __android_log_print(ANDROID_LOG_INFO, "bugsplat-android", "Attachment path: %s", pathStr);
        
        // Check if the file exists and is readable
        if (access(pathStr, R_OK) == 0) {
            attachmentPaths.push_back(FilePath(pathStr));
            __android_log_print(ANDROID_LOG_INFO, "bugsplat-android", "Attachment file exists and is readable");
        } else {
            __android_log_print(ANDROID_LOG_WARN, "bugsplat-android", "Attachment file does not exist or is not readable: %s", pathStr);
        }
        
        env->ReleaseStringUTFChars(path, pathStr);
        env->DeleteLocalRef(path);
    }
    
    return attachmentPaths;
}