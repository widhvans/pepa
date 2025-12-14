package com.provideoplayer

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.provideoplayer.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences

    companion object {
        const val PREFS_NAME = "pro_video_player_prefs"
        const val KEY_THEME = "theme_mode"
        const val KEY_DEFAULT_SPEED = "default_speed"
        const val KEY_RESUME_PLAYBACK = "resume_playback"
        const val KEY_AUTO_PLAY_NEXT = "auto_play_next"
        const val KEY_GESTURE_CONTROLS = "gesture_controls"
        const val KEY_DOUBLE_TAP_SEEK = "double_tap_seek"
        const val KEY_AUTO_PIP = "auto_pip"
        const val KEY_HARDWARE_ACCELERATION = "hardware_acceleration"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved theme before calling super.onCreate
        applyAppTheme()
        
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setupToolbar()
        loadSettings()
        setupListeners()
    }
    
    private fun applyAppTheme() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val themeMode = prefs.getInt(KEY_THEME, 0)
        
        when (themeMode) {
            0 -> { /* System Default - no override */ }
            1 -> setTheme(R.style.Theme_ProVideoPlayer_Light)
            2 -> setTheme(R.style.Theme_ProVideoPlayer)  // Dark
            3 -> setTheme(R.style.Theme_ProVideoPlayer_Amoled)
            4 -> setTheme(R.style.Theme_ProVideoPlayer_Blue)
            5 -> setTheme(R.style.Theme_ProVideoPlayer_Pink)
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Settings"
        }
        binding.toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
    }

    private fun loadSettings() {
        // Load saved preferences
        binding.switchResumePlayback.isChecked = prefs.getBoolean(KEY_RESUME_PLAYBACK, true)
        binding.switchAutoPlayNext.isChecked = prefs.getBoolean(KEY_AUTO_PLAY_NEXT, true)
        binding.switchGestureControls.isChecked = prefs.getBoolean(KEY_GESTURE_CONTROLS, true)
        binding.switchDoubleTapSeek.isChecked = prefs.getBoolean(KEY_DOUBLE_TAP_SEEK, true)
        binding.switchAutoPip.isChecked = prefs.getBoolean(KEY_AUTO_PIP, true)
        binding.switchHardwareAcceleration.isChecked = prefs.getBoolean(KEY_HARDWARE_ACCELERATION, true)

        // Update speed text
        updateSpeedText()
    }

    private fun setupListeners() {
        // Default speed selection
        binding.layoutDefaultSpeed.setOnClickListener {
            showSpeedDialog()
        }

        // Resume playback
        binding.switchResumePlayback.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_RESUME_PLAYBACK, isChecked).apply()
        }

        // Auto play next
        binding.switchAutoPlayNext.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_PLAY_NEXT, isChecked).apply()
        }

        // Gesture controls
        binding.switchGestureControls.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_GESTURE_CONTROLS, isChecked).apply()
        }

        // Double tap seek
        binding.switchDoubleTapSeek.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_DOUBLE_TAP_SEEK, isChecked).apply()
        }

        // Auto PiP
        binding.switchAutoPip.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_PIP, isChecked).apply()
        }

        // Hardware acceleration
        binding.switchHardwareAcceleration.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_HARDWARE_ACCELERATION, isChecked).apply()
        }

        // Clear cache
        binding.layoutClearCache.setOnClickListener {
            clearCache()
        }

        // About
        binding.layoutAbout.setOnClickListener {
            showAboutDialog()
        }
    }

    private fun showSpeedDialog() {
        val speeds = arrayOf("0.5x", "0.75x", "1.0x (Normal)", "1.25x", "1.5x", "2.0x")
        val speedValues = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val currentSpeed = prefs.getFloat(KEY_DEFAULT_SPEED, 1.0f)
        val currentIndex = speedValues.indexOfFirst { it == currentSpeed }.takeIf { it >= 0 } ?: 2

        MaterialAlertDialogBuilder(this)
            .setTitle("Default Playback Speed")
            .setSingleChoiceItems(speeds, currentIndex) { dialog, which ->
                prefs.edit().putFloat(KEY_DEFAULT_SPEED, speedValues[which]).apply()
                updateSpeedText()
                dialog.dismiss()
            }
            .show()
    }

    private fun updateSpeedText() {
        val currentSpeed = prefs.getFloat(KEY_DEFAULT_SPEED, 1.0f)
        binding.textSpeedValue.text = "${currentSpeed}x"
    }

    private fun clearCache() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear Cache")
            .setMessage("This will clear all cached thumbnails and temporary files. Continue?")
            .setPositiveButton("Clear") { _, _ ->
                try {
                    cacheDir.deleteRecursively()
                    cacheDir.mkdirs()
                    Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to clear cache", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Akplayer")
            .setMessage("""
                Version: 1.0.0
                
                A professional video player with:
                • High quality video playback
                • Gesture controls
                • Subtitle support
                • Multiple audio tracks
                • Network streaming
                • Picture-in-Picture mode
                • Multiple Themes
                
                Powered by ExoPlayer
            """.trimIndent())
            .setPositiveButton("OK", null)
            .show()
    }
}
