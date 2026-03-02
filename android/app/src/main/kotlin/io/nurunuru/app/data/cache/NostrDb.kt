package io.nurunuru.app.data.cache

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Kotlin wrapper for nostrdb JNI.
 */
object NostrDb {
    private const val TAG = "NostrDb"
    private var isInitialized = false

    init {
        try {
            System.loadLibrary("nostrdb_jni")
            Log.i(TAG, "Native library nostrdb_jni loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library nostrdb_jni", e)
        }
    }

    /**
     * Initialize nostrdb.
     * @param context Application context to get filesDir.
     * @param mapSize Database size in bytes. Default 1GB.
     */
    fun init(context: Context, mapSize: Long = 1024L * 1024 * 1024) {
        if (isInitialized) return

        val dbDir = File(context.filesDir, "nostrdb_ndb")
        if (!dbDir.exists()) {
            dbDir.mkdirs()
        }

        try {
            init(dbDir.absolutePath, mapSize)
            isInitialized = true
            Log.i(TAG, "nostrdb initialized at ${dbDir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing nostrdb", e)
        }
    }

    fun processEvent(json: String): Int {
        if (!isInitialized) return -1
        return try {
            nativeProcessEvent(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing event", e)
            -1
        }
    }

    fun query(filterJson: String): List<String> {
        if (!isInitialized) return emptyList()
        return try {
            nativeQuery(filterJson)
        } catch (e: Exception) {
            Log.e(TAG, "Error querying events", e)
            emptyList()
        }
    }

    fun close() {
        if (!isInitialized) return
        try {
            nativeClose()
            isInitialized = false
        } catch (e: Exception) {
            Log.e(TAG, "Error closing nostrdb", e)
        }
    }

    private external fun init(dbPath: String, mapSize: Long)

    @JvmStatic
    private external fun nativeProcessEvent(json: String): Int

    @JvmStatic
    private external fun nativeQuery(filterJson: String): List<String>

    @JvmStatic
    private external fun nativeClose()
}
