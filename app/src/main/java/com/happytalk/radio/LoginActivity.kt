package com.happytalk.radio

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.appwrite.enums.OAuthProvider

class LoginActivity : AppCompatActivity() {

    private lateinit var btnGoogleLogin: Button
    private lateinit var pbLogin: ProgressBar
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isAuthenticating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        AppwriteManager.init(this)

        btnGoogleLogin = findViewById(R.id.btnGoogleLogin)
        pbLogin = findViewById(R.id.pbLogin)

        btnGoogleLogin.setOnClickListener {
            startGoogleLogin()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isAuthenticating) {
            checkSession()
        }
    }

    private fun startGoogleLogin() {
        isAuthenticating = true
        btnGoogleLogin.isEnabled = false
        pbLogin.visibility = View.VISIBLE

        scope.launch {
            try {
                // In Appwrite Android SDK, this launches the browser.
                // The Coroutine will resume once the browser redirects back to the app.
                withContext(Dispatchers.IO) {
                    AppwriteManager.account.createOAuth2Session(
                        activity = this@LoginActivity,
                        provider = OAuthProvider.GOOGLE
                    )
                }
                checkSession()
            } catch (e: Exception) {
                Log.e("LoginActivity", "OAuth flow failed or cancelled", e)
                btnGoogleLogin.isEnabled = true
                pbLogin.visibility = View.GONE
            }
        }
    }

    private fun checkSession() {
        scope.launch {
            try {
                val user = withContext(Dispatchers.IO) {
                    AppwriteManager.account.get()
                }
                Log.i("LoginActivity", "Logged in as ${user.name}")
                // Save the account name as the default nick name
                val prefs = getSharedPreferences("RadioPrefs", MODE_PRIVATE)
                prefs.edit().putString("currentNickName", user.name).apply()

                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                finish()
            } catch (e: Exception) {
                Log.e("LoginActivity", "Session check failed", e)
                btnGoogleLogin.isEnabled = true
                pbLogin.visibility = View.GONE
                // Only show toast if we were actively authenticating and it failed
                if (isAuthenticating) {
                    Toast.makeText(this@LoginActivity, "Login failed or cancelled", Toast.LENGTH_SHORT).show()
                    isAuthenticating = false
                }
            }
        }
    }
}
