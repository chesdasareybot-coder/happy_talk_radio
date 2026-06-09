package com.happytalk.radio

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("ThemePrefs", MODE_PRIVATE)
        val isDarkMode = if (prefs.contains("isDarkMode")) prefs.getBoolean("isDarkMode", false)
        else (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Play radio chirp sound effect
        Thread {
            try {
                val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)
                toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 80)
                Thread.sleep(120)
                toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 80)
                Thread.sleep(120)
                toneGen.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()

        val ivLogo = findViewById<ImageView>(R.id.ivSplashLogo)
        val tvTitle = findViewById<TextView>(R.id.tvSplashTitle)
        val ivRing = findViewById<ImageView>(R.id.ivSplashRing)

        // Initial state
        ivLogo.scaleX = 0f
        ivLogo.scaleY = 0f
        ivLogo.alpha = 0f
        tvTitle.alpha = 0f
        tvTitle.translationY = 50f
        ivRing.alpha = 0f
        ivRing.scaleX = 0.5f
        ivRing.scaleY = 0.5f

        // Animation for Logo
        val logoScaleX = ObjectAnimator.ofFloat(ivLogo, "scaleX", 0f, 1f)
        val logoScaleY = ObjectAnimator.ofFloat(ivLogo, "scaleY", 0f, 1f)
        val logoFade = ObjectAnimator.ofFloat(ivLogo, "alpha", 0f, 1f)

        // Animation for Title
        val titleFade = ObjectAnimator.ofFloat(tvTitle, "alpha", 0f, 1f)
        val titleSlide = ObjectAnimator.ofFloat(tvTitle, "translationY", 50f, 0f)

        val animatorSet = AnimatorSet().apply {
            playTogether(logoScaleX, logoScaleY, logoFade, titleFade, titleSlide)
            duration = 1000
            interpolator = OvershootInterpolator(1.2f)
        }

        animatorSet.start()

        // Pulse animation for Ring (starts after initial animation)
        val ringScaleX = ObjectAnimator.ofFloat(ivRing, "scaleX", 0.8f, 1.4f)
        val ringScaleY = ObjectAnimator.ofFloat(ivRing, "scaleY", 0.8f, 1.4f)
        val ringFade = ObjectAnimator.ofFloat(ivRing, "alpha", 0.6f, 0f)
        
        val pulseSet = AnimatorSet().apply {
            playTogether(ringScaleX, ringScaleY, ringFade)
            duration = 1200
            startDelay = 500
        }
        
        // Loop pulse animation manually
        ringFade.repeatMode = android.animation.ValueAnimator.RESTART
        ringFade.repeatCount = android.animation.ValueAnimator.INFINITE
        ringScaleX.repeatMode = android.animation.ValueAnimator.RESTART
        ringScaleX.repeatCount = android.animation.ValueAnimator.INFINITE
        ringScaleY.repeatMode = android.animation.ValueAnimator.RESTART
        ringScaleY.repeatCount = android.animation.ValueAnimator.INFINITE
        
        pulseSet.start()

        // Navigate to MainActivity after 2.5 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 2500)
    }
}
