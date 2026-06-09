package com.happytalk.radio

import android.content.Context
import android.util.Log
import io.appwrite.Client
import io.appwrite.services.Databases
import io.appwrite.services.Realtime
import io.appwrite.services.Storage

/**
 * Singleton manager for Appwrite SDK — client-side access only (no API key).
 */
object AppwriteManager {

    private const val TAG            = "AppwriteManager"
    const val ENDPOINT               = "https://sgp.cloud.appwrite.io/v1"
    const val PROJECT_ID             = "6a24f3af00123cb02a39"

    lateinit var client:    Client    private set
    lateinit var databases: Databases private set
    lateinit var realtime:  Realtime  private set
    lateinit var storage:   Storage   private set

    fun init(context: Context) {
        if (::client.isInitialized) return
        client = Client(context.applicationContext)
            .setEndpoint(ENDPOINT)
            .setProject(PROJECT_ID)
        databases = Databases(client)
        realtime  = Realtime(client)
        storage   = Storage(client)
        Log.i(TAG, "Appwrite initialized — endpoint=$ENDPOINT project=$PROJECT_ID")
    }
}
