#include <android/log.h>
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "include/bugsplat_attachments.h"

#define LOG_TAG "bugsplat-handler"
#define MAX_EXTRA_ATTACHMENTS 256

// Crashpad copies --attachment paths into the handler argv at
// StartHandlerAtCrash() time and never reads them again. This wrapper is
// exec'd instead of libcrashpad_handler.so so we can append --attachment
// arguments from a list file the SDK updates after init.
//
// Flow: read --database= from argv, load <database>/bugsplat_attachments.list,
// exec libcrashpad_handler.so (same directory) with the extra arguments.

static const char* findDatabaseArg(int argc, char** argv) {
    static const char kPrefix[] = "--database=";
    const size_t prefixLen = sizeof(kPrefix) - 1;
    for (int i = 1; i < argc; i++) {
        if (strncmp(argv[i], kPrefix, prefixLen) == 0) {
            return argv[i] + prefixLen;
        }
    }
    return nullptr;
}

static bool handlerFromExePath(const char* exe, char* out, size_t outSize) {
    if (exe == nullptr || exe[0] == '\0') {
        return false;
    }

    char buf[PATH_MAX];
    if (strlen(exe) >= sizeof(buf)) {
        return false;
    }
    memcpy(buf, exe, strlen(exe) + 1);

    // readlink("/proc/self/exe") can append " (deleted)" after an in-place update.
    char* deleted = strstr(buf, " (deleted)");
    if (deleted != nullptr) {
        *deleted = '\0';
    }

    char* slash = strrchr(buf, '/');
    if (slash == nullptr) {
        return false;
    }
    *slash = '\0';

    int written = snprintf(out, outSize, "%s/libcrashpad_handler.so", buf);
    if (written < 0 || static_cast<size_t>(written) >= outSize) {
        return false;
    }
    return access(out, F_OK) == 0;
}

static int realHandlerPath(int argc, char** argv, char* out, size_t outSize) {
    if (argc > 0 && handlerFromExePath(argv[0], out, outSize)) {
        return 0;
    }

    char self[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", self, sizeof(self) - 1);
    if (n > 0) {
        self[n] = '\0';
        if (handlerFromExePath(self, out, outSize)) {
            return 0;
        }
    }
    return -1;
}

static void trimTrailing(char* line) {
    size_t len = strlen(line);
    while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) {
        line[--len] = '\0';
    }
}

int main(int argc, char** argv) {
    char handler[PATH_MAX];
    if (realHandlerPath(argc, argv, handler, sizeof(handler)) != 0) {
        // argv[0] is this wrapper; re-execing it would loop. A dump still
        // requires locating libcrashpad_handler.so next to us.
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "could not resolve libcrashpad_handler.so");
        return 1;
    }

    const char* database = findDatabaseArg(argc, argv);
    char* extraArgs[MAX_EXTRA_ATTACHMENTS];
    int extraCount = 0;

    if (database != nullptr && database[0] != '\0') {
        char listPath[PATH_MAX];
        int written = snprintf(listPath, sizeof(listPath), "%s/%s", database,
                               kAttachmentsListFileName);
        if (written > 0 && static_cast<size_t>(written) < sizeof(listPath)) {
            FILE* file = fopen(listPath, "r");
            if (file != nullptr) {
                char line[PATH_MAX];
                while (extraCount < MAX_EXTRA_ATTACHMENTS &&
                       fgets(line, sizeof(line), file) != nullptr) {
                    trimTrailing(line);
                    if (line[0] == '\0') {
                        continue;
                    }
                    size_t argLen = strlen("--attachment=") + strlen(line) + 1;
                    char* arg = static_cast<char*>(malloc(argLen));
                    if (arg == nullptr) {
                        break;
                    }
                    snprintf(arg, argLen, "--attachment=%s", line);
                    extraArgs[extraCount++] = arg;
                }
                fclose(file);
            }
        }
    }

    // Original argv plus extra attachments plus terminating nullptr.
    int newArgc = argc + extraCount;
    char** newArgv = static_cast<char**>(calloc(static_cast<size_t>(newArgc) + 1,
                                                sizeof(char*)));
    if (newArgv == nullptr) {
        execv(handler, argv);
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "execv(%s) failed: %s", handler, strerror(errno));
        return 1;
    }

    newArgv[0] = handler;
    for (int i = 1; i < argc; i++) {
        newArgv[i] = argv[i];
    }
    for (int i = 0; i < extraCount; i++) {
        newArgv[argc + i] = extraArgs[i];
    }

    execv(handler, newArgv);
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "execv(%s) failed: %s",
                        handler, strerror(errno));
    return 1;
}
