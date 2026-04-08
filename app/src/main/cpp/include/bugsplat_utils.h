#ifndef BUGSPLAT_UTILS_H
#define BUGSPLAT_UTILS_H

#include <jni.h>
#include <string>
#include <map>
#include <vector>
#include "client/crashpad_client.h"

using namespace base;
using namespace crashpad;
using namespace std;

/**
 * Creates custom attributes from a Java Map and adds them to the global AnnotationList
 *
 * @param env JNI environment
 * @param attributes_map Java Map<String, String> containing custom attributes
 */
void createAttributes(JNIEnv *env, jobject attributes_map);

/**
 * Creates a vector of file paths from a Java String array
 * 
 * @param env JNI environment
 * @param attachments Java String array containing file paths
 * @return Vector of FilePath objects
 */
vector<FilePath> createAttachments(JNIEnv *env, jobjectArray attachments);

#endif // BUGSPLAT_UTILS_H 