package com.happytalk.radio

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * WorkManager worker that deletes an audio file from Appwrite Storage
 * and its metadata document from the audio_messages collection.
 * Scheduled to run 60 seconds after upload — survives app closure.
 */
class AudioDeleteWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val fileId  = inputData.getString(KEY_FILE_ID)   ?: return Result.failure()
        val docId   = inputData.getString(KEY_DOC_ID)    ?: return Result.failure()
        val bucket  = inputData.getString(KEY_BUCKET)    ?: return Result.failure()
        val dbId    = inputData.getString(KEY_DB_ID)     ?: return Result.failure()

        AppwriteManager.init(applicationContext)

        // Delete storage file
        runCatching {
            AppwriteManager.storage.deleteFile(bucket, fileId)
            Log.i("AudioDeleteWorker", "Deleted storage file: $fileId")
        }.onFailure { Log.e("AudioDeleteWorker", "Failed to delete file $fileId", it) }

        // Delete audio_messages document
        runCatching {
            AppwriteManager.databases.deleteDocument(dbId, "audio_messages", docId)
            Log.i("AudioDeleteWorker", "Deleted audio_messages doc: $docId")
        }.onFailure { Log.e("AudioDeleteWorker", "Failed to delete doc $docId", it) }

        return Result.success()
    }

    companion object {
        const val KEY_FILE_ID = "fileId"
        const val KEY_DOC_ID  = "docId"
        const val KEY_BUCKET  = "bucket"
        const val KEY_DB_ID   = "dbId"
    }
}
