package com.happytalk.radio

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GitHubUpdater {
    
    // Automatically configured for chesdasareybot-coder's repository
    private const val GITHUB_OWNER = "chesdasareybot-coder"
    private const val GITHUB_REPO = "happy_talk_radio"
    
    private const val API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    suspend fun checkForUpdates(context: Context, currentVersion: String) {
        if (GITHUB_OWNER == "YOUR_GITHUB_USERNAME") {
            Log.w("GitHubUpdater", "Updater skipped: Please configure GITHUB_OWNER in GitHubUpdater.kt")
            return
        }

        try {
            val response = withContext(Dispatchers.IO) {
                val url = URL(API_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else null
            } ?: return

            val json = JSONObject(response)
            val latestVersion = json.getString("tag_name").removePrefix("v")
            
            // Compare versions (simple string comparison for semantic versioning)
            if (latestVersion != currentVersion && isNewer(currentVersion, latestVersion)) {
                val assets = json.getJSONArray("assets")
                var downloadUrl: String? = null
                
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (name.endsWith(".apk")) {
                        downloadUrl = asset.getString("browser_download_url")
                        break
                    }
                }
                
                if (downloadUrl != null) {
                    withContext(Dispatchers.Main) {
                        showUpdateDialog(context, latestVersion, downloadUrl)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GitHubUpdater", "Failed to check for updates", e)
        }
    }

    private fun isNewer(current: String, latest: String): Boolean {
        // Very basic semantic version comparison
        try {
            val currParts = current.split(".").map { it.toIntOrNull() ?: 0 }
            val lateParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
            
            val maxLength = maxOf(currParts.size, lateParts.size)
            for (i in 0 until maxLength) {
                val c = currParts.getOrElse(i) { 0 }
                val l = lateParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
        } catch (e: Exception) {
            Log.e("GitHubUpdater", "Version compare error", e)
        }
        return false
    }

    private fun showUpdateDialog(context: Context, version: String, url: String) {
        AlertDialog.Builder(context)
            .setTitle("New Update Available!")
            .setMessage("HappyTalk Radio version $version is ready to download. Update now?")
            .setPositiveButton("Update") { _, _ ->
                startDownload(context, url)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun startDownload(context: Context, urlString: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(urlString))
                .setTitle("HappyTalk Radio Update")
                .setDescription("Downloading latest version...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "HappyTalkRadio_Update.apk")
                .setMimeType("application/vnd.android.package-archive")

            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            
            // The MainActivity will listen for the DOWNLOAD_COMPLETE broadcast to install it
        } catch (e: Exception) {
            Log.e("GitHubUpdater", "Failed to start download", e)
        }
    }
}
