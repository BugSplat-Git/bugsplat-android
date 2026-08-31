#include <android/log.h>
#include <cstdio>
#include <jni.h>
#include <map>
#include <pthread.h>
#include <string>
#include <unistd.h>
#include <vector>
#include "client/annotation.h"
#include "client/annotation_list.h"
#include "client/crashpad_client.h"
#include "client/crashpad_info.h"
#include "client/crash_report_database.h"
#include "client/settings.h"
#include "include/bugsplat_attachments.h"
#include "include/bugsplat_utils.h"

using namespace base;
using namespace crashpad;
using namespace std;

// Holds a dynamically created Annotation with its own name and value storage.
// Each instance self-registers with the global AnnotationList on first SetSize().
struct DynamicAnnotation {
    char name[256];
    char value[256];
    Annotation annotation;

    DynamicAnnotation(const char* key, const char* val)
        : annotation(Annotation::Type::kString, name, value) {
        strncpy(name, key, sizeof(name) - 1);
        name[sizeof(name) - 1] = '\0';
        SetValue(val);
    }

    void SetValue(const char* val) {
        strncpy(value, val, sizeof(value) - 1);
        value[sizeof(value) - 1] = '\0';
        annotation.SetSize(strlen(value));
    }

    void Clear() {
        annotation.Clear();
    }
};

static map<string, DynamicAnnotation*>* g_annotations = nullptr;

static pthread_mutex_t g_attachments_mutex = PTHREAD_MUTEX_INITIALIZER;
static vector<string>* g_attachments = nullptr;
static string g_attachments_list_path;

static bool persistAttachmentsLocked();
static bool addAttachmentPath(const char* path);
static void removeAttachmentPath(const char* path);

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

extern "C" JNIEXPORT void JNICALL
Java_com_bugsplat_android_BugSplatBridge_jniHang(JNIEnv *env, jclass clazz);

extern "C" JNIEXPORT void JNICALL
Java_com_bugsplat_android_BugSplatBridge_jniSetAttribute(JNIEnv *env, jclass clazz,
                                                         jstring key, jstring value);

extern "C" JNIEXPORT void JNICALL
Java_com_bugsplat_android_BugSplatBridge_jniRemoveAttribute(JNIEnv *env, jclass clazz,
                                                            jstring key);

extern "C" JNIEXPORT void JNICALL
Java_com_bugsplat_android_BugSplatBridge_jniAddAttachment(JNIEnv *env, jclass clazz,
                                                         jstring path);

extern "C" JNIEXPORT void JNICALL
Java_com_bugsplat_android_BugSplatBridge_jniRemoveAttachment(JNIEnv *env, jclass clazz,
                                                            jstring path);

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

    // Crashpad file paths. Prefer the wrapper so post-init add/remove of
    // attachments is reflected at crash time; fall back to the stock handler.
    FilePath crashpadHandler(libDir + "/libcrashpad_handler.so");
    FilePath wrapperHandler(libDir + "/libbugsplat_handler.so");
    bool useWrapper = access(wrapperHandler.value().c_str(), F_OK) == 0;
    FilePath handler = useWrapper ? wrapperHandler : crashpadHandler;
    FilePath reportsDir(dataDir + "/crashpad");
    FilePath metricsDir(dataDir + "/crashpad");

    string databaseString = env->GetStringUTFChars(database, nullptr);

    // Crashpad upload URL for BugSplat database
    string url = "https://" + databaseString + ".bugsplat.com/post/bp/crash/crashpad.php";
    __android_log_print(ANDROID_LOG_INFO, "bugsplat-android", "Url: %s", url.c_str());

    // Crashpad annotations (passed to StartHandlerAtCrash for upload metadata)
    map<string, string> annotations;
    annotations["format"] = "minidump";
    annotations["database"] = databaseString;
    annotations["product"] = env->GetStringUTFChars(application, nullptr);
    annotations["version"] = env->GetStringUTFChars(version, nullptr);

    // Register an AnnotationList for runtime-updatable annotations.
    // Unlike the annotations map passed to StartHandlerAtCrash, these live in process memory
    // and can be modified at any time — the crash handler reads them directly at crash time.
    AnnotationList::Register();
    g_annotations = new map<string, DynamicAnnotation*>();
    for (const auto& entry : annotations) {
        auto* da = new DynamicAnnotation(entry.first.c_str(), entry.second.c_str());
        (*g_annotations)[entry.first] = da;
    }

    // Add custom attributes to the AnnotationList only (not the StartHandlerAtCrash
    // annotations map) so they can be overridden at runtime via setAttribute.
    createAttributes(env, attributes_map);

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

    // Attachment paths are resolved at crash time (files do not need to exist
    // yet). The wrapper reads this list when it execs crashpad_handler; without
    // the wrapper, Crashpad snapshots the paths into argv here and they cannot
    // be updated later.
    pthread_mutex_lock(&g_attachments_mutex);
    g_attachments = new vector<string>(createAttachments(env, attachments));
    g_attachments_list_path = reportsDir.value() + "/" + kAttachmentsListFileName;
    persistAttachmentsLocked();
    vector<FilePath> attachmentPaths;
    if (!useWrapper) {
        for (const auto& path : *g_attachments) {
            attachmentPaths.emplace_back(path);
        }
    }
    pthread_mutex_unlock(&g_attachments_mutex);

    if (useWrapper) {
        __android_log_print(ANDROID_LOG_INFO, "bugsplat-android",
                            "Using attachment wrapper: %s", handler.value().c_str());
    } else {
        __android_log_print(ANDROID_LOG_WARN, "bugsplat-android",
                            "libbugsplat_handler.so missing; post-init attachments will not apply");
    }

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

extern "C" JNIEXPORT void JNICALL
Java_com_bugsplat_android_BugSplatBridge_jniHang(JNIEnv *env, jclass clazz)
{
    volatile int counter = 0;
    while (true) {
        counter++;
    }
}

// Utility function implementations
void createAttributes(JNIEnv *env, jobject attributes_map) {
    if (attributes_map == nullptr || g_annotations == nullptr) {
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

        // Add to AnnotationList
        auto* da = new DynamicAnnotation(keyStr, valueStr);
        (*g_annotations)[keyStr] = da;

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

extern "C" JNIEXPORT void JNICALL
Java_com_bugsplat_android_BugSplatBridge_jniSetAttribute(JNIEnv *env, jclass clazz,
                                                         jstring key, jstring value) {
    if (g_annotations == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, "bugsplat-android", "setAttribute called before init");
        return;
    }

    const char* keyStr = env->GetStringUTFChars(key, nullptr);
    const char* valueStr = env->GetStringUTFChars(value, nullptr);

    auto it = g_annotations->find(keyStr);
    if (it != g_annotations->end()) {
        it->second->SetValue(valueStr);
    } else {
        auto* da = new DynamicAnnotation(keyStr, valueStr);
        (*g_annotations)[keyStr] = da;
    }

    env->ReleaseStringUTFChars(key, keyStr);
    env->ReleaseStringUTFChars(value, valueStr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bugsplat_android_BugSplatBridge_jniRemoveAttribute(JNIEnv *env, jclass clazz,
                                                            jstring key) {
    if (g_annotations == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, "bugsplat-android", "removeAttribute called before init");
        return;
    }

    const char* keyStr = env->GetStringUTFChars(key, nullptr);

    auto it = g_annotations->find(keyStr);
    if (it != g_annotations->end()) {
        it->second->Clear();
    }

    env->ReleaseStringUTFChars(key, keyStr);
}

vector<string> createAttachments(JNIEnv *env, jobjectArray attachments) {
    vector<string> attachmentPaths;

    if (attachments == nullptr) {
        return attachmentPaths;
    }

    jsize length = env->GetArrayLength(attachments);
    for (jsize i = 0; i < length; i++) {
        jstring path = (jstring)env->GetObjectArrayElement(attachments, i);
        if (path == nullptr) {
            continue;
        }
        const char* pathStr = env->GetStringUTFChars(path, nullptr);

        if (pathStr != nullptr && pathStr[0] != '\0') {
            __android_log_print(ANDROID_LOG_INFO, "bugsplat-android",
                                "Attachment path: %s", pathStr);
            attachmentPaths.emplace_back(pathStr);
        }

        env->ReleaseStringUTFChars(path, pathStr);
        env->DeleteLocalRef(path);
    }

    return attachmentPaths;
}

static bool persistAttachmentsLocked() {
    if (g_attachments == nullptr || g_attachments_list_path.empty()) {
        return false;
    }

    string tmpPath = g_attachments_list_path + ".tmp";
    FILE* file = fopen(tmpPath.c_str(), "w");
    if (file == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, "bugsplat-android",
                            "Failed to write attachments list: %s", tmpPath.c_str());
        return false;
    }

    for (const auto& path : *g_attachments) {
        fprintf(file, "%s\n", path.c_str());
    }

    int flushResult = fflush(file);
    int syncResult = fsync(fileno(file));
    fclose(file);
    if (flushResult != 0 || syncResult != 0) {
        unlink(tmpPath.c_str());
        __android_log_print(ANDROID_LOG_ERROR, "bugsplat-android",
                            "Failed to flush attachments list");
        return false;
    }

    if (rename(tmpPath.c_str(), g_attachments_list_path.c_str()) != 0) {
        unlink(tmpPath.c_str());
        __android_log_print(ANDROID_LOG_ERROR, "bugsplat-android",
                            "Failed to replace attachments list");
        return false;
    }
    return true;
}

static bool addAttachmentPath(const char* path) {
    pthread_mutex_lock(&g_attachments_mutex);
    if (g_attachments == nullptr) {
        pthread_mutex_unlock(&g_attachments_mutex);
        return false;
    }

    for (const auto& existing : *g_attachments) {
        if (existing == path) {
            pthread_mutex_unlock(&g_attachments_mutex);
            return true;
        }
    }

    g_attachments->emplace_back(path);
    persistAttachmentsLocked();
    pthread_mutex_unlock(&g_attachments_mutex);
    return true;
}

static void removeAttachmentPath(const char* path) {
    pthread_mutex_lock(&g_attachments_mutex);
    if (g_attachments == nullptr) {
        pthread_mutex_unlock(&g_attachments_mutex);
        return;
    }

    auto it = g_attachments->begin();
    while (it != g_attachments->end()) {
        if (*it == path) {
            it = g_attachments->erase(it);
        } else {
            ++it;
        }
    }
    persistAttachmentsLocked();
    pthread_mutex_unlock(&g_attachments_mutex);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bugsplat_android_BugSplatBridge_jniAddAttachment(JNIEnv *env, jclass clazz,
                                                         jstring path) {
    if (path == nullptr) {
        return;
    }
    const char* pathStr = env->GetStringUTFChars(path, nullptr);
    if (pathStr == nullptr) {
        return;
    }

    if (!addAttachmentPath(pathStr)) {
        __android_log_print(ANDROID_LOG_WARN, "bugsplat-android",
                            "addAttachment called before init");
    } else {
        __android_log_print(ANDROID_LOG_INFO, "bugsplat-android",
                            "Added attachment: %s", pathStr);
    }

    env->ReleaseStringUTFChars(path, pathStr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bugsplat_android_BugSplatBridge_jniRemoveAttachment(JNIEnv *env, jclass clazz,
                                                            jstring path) {
    if (path == nullptr) {
        return;
    }
    const char* pathStr = env->GetStringUTFChars(path, nullptr);
    if (pathStr == nullptr) {
        return;
    }

    if (g_attachments == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, "bugsplat-android",
                            "removeAttachment called before init");
    } else {
        removeAttachmentPath(pathStr);
        __android_log_print(ANDROID_LOG_INFO, "bugsplat-android",
                            "Removed attachment: %s", pathStr);
    }

    env->ReleaseStringUTFChars(path, pathStr);
}