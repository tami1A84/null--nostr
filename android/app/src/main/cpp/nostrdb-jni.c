#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <android/log.h>
#include "nostrdb/src/nostrdb.h"
#include <malloc.h>

#define TAG "nostrdb-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct ndb *g_ndb = NULL;

JNIEXPORT void JNICALL
Java_io_nurunuru_app_data_cache_NostrDb_init(JNIEnv *env, jobject thiz, jstring db_path, jlong mapsize) {
    const char *path = (*env)->GetStringUTFChars(env, db_path, NULL);

    LOGI("Initializing nostrdb at %s with mapsize %ld", path, (long)mapsize);

    int res = ndb_init(&g_ndb, path, mapsize, 0, NULL);
    if (res != 0) {
        LOGE("Failed to initialize nostrdb: %d", res);
    } else {
        LOGI("nostrdb initialized successfully");
    }

    (*env)->ReleaseStringUTFChars(env, db_path, path);
}

JNIEXPORT jint JNICALL
Java_io_nurunuru_app_data_cache_NostrDb_nativeProcessEvent(JNIEnv *env, jclass clazz, jstring json) {
    if (!g_ndb) {
        LOGE("nostrdb not initialized");
        return -1;
    }

    const char *ev_json = (*env)->GetStringUTFChars(env, json, NULL);
    int len = strlen(ev_json);

    int res = ndb_process_event(g_ndb, ev_json, len);

    (*env)->ReleaseStringUTFChars(env, json, ev_json);
    return res;
}

JNIEXPORT void JNICALL
Java_io_nurunuru_app_data_cache_NostrDb_nativeClose(JNIEnv *env, jclass clazz) {
    if (g_ndb) {
        ndb_destroy(g_ndb);
        g_ndb = NULL;
        LOGI("nostrdb closed");
    }
}

// Helper to convert NDB query results to Kotlin List<String>
JNIEXPORT jobject JNICALL
Java_io_nurunuru_app_data_cache_NostrDb_nativeQuery(JNIEnv *env, jclass clazz, jstring filter_json) {
    if (!g_ndb) {
        LOGE("nostrdb not initialized");
        return NULL;
    }

    const char *filter_str = (*env)->GetStringUTFChars(env, filter_json, NULL);
    int filter_len = strlen(filter_str);

    struct ndb_filter filter;
    unsigned char filter_buf[1024]; // Buffer for filter construction
    int res = ndb_filter_from_json(filter_str, filter_len, &filter, filter_buf, sizeof(filter_buf));
    (*env)->ReleaseStringUTFChars(env, filter_json, filter_str);

    if (res < 0) {
        LOGE("Failed to parse filter JSON: %d", res);
        return NULL;
    }

    struct ndb_txn txn;
    ndb_begin_query(g_ndb, &txn);

    int capacity = 100;
    struct ndb_query_result *results = malloc(sizeof(struct ndb_query_result) * capacity);
    int count = 0;

    res = ndb_query(&txn, &filter, 1, results, capacity, &count);

    jclass arrayListClass = (*env)->FindClass(env, "java/util/ArrayList");
    jmethodID arrayListInit = (*env)->GetMethodID(env, arrayListClass, "<init>", "()V");
    jmethodID arrayListAdd = (*env)->GetMethodID(env, arrayListClass, "add", "(Ljava/lang/Object;)Z");
    jobject arrayList = (*env)->NewObject(env, arrayListClass, arrayListInit);

    if (res >= 0) {
        char *json_buf = malloc(1024 * 64); // 64KB buffer for note JSON
        for (int i = 0; i < count; i++) {
            int json_len = ndb_note_json(results[i].note, json_buf, 1024 * 64);
            if (json_len > 0) {
                jstring jstr = (*env)->NewStringUTF(env, json_buf);
                (*env)->CallBooleanMethod(env, arrayList, arrayListAdd, jstr);
                (*env)->DeleteLocalRef(env, jstr);
            }
        }
        free(json_buf);
    } else {
        LOGE("Query failed: %d", res);
    }

    free(results);
    ndb_end_query(&txn);

    return arrayList;
}
