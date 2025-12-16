package com.provideoplayer.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Handles runtime permissions for storage and media access
 */
object PermissionManager {
    
    const val STORAGE_PERMISSION_CODE = 100
    const val ALL_FILES_ACCESS_CODE = 101
    
    /**
     * Get required permissions based on Android version
     */
    fun getRequiredPermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+ uses granular media permissions
                arrayOf(
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                // Android 11-12 only needs READ
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            else -> {
                // Android 10 and below
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            }
        }
    }
    
    /**
     * Check if All Files Access is granted (Android 11+)
     */
    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // Not needed for older versions
        }
    }
    
    /**
     * Request All Files Access permission (Android 11+)
     */
    fun requestAllFilesAccess(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:${activity.packageName}")
                activity.startActivityForResult(intent, ALL_FILES_ACCESS_CODE)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                activity.startActivityForResult(intent, ALL_FILES_ACCESS_CODE)
            }
        }
    }
    
    /**
     * Check if all required permissions are granted
     */
    fun hasStoragePermission(context: Context): Boolean {
        val permissions = getRequiredPermissions()
        val basicPermissions = permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        
        // Also check All Files Access for Android 11+
        return basicPermissions && hasAllFilesAccess()
    }
    
    /**
     * Request storage permissions
     */
    fun requestStoragePermission(activity: Activity) {
        val permissions = getRequiredPermissions()
        ActivityCompat.requestPermissions(activity, permissions, STORAGE_PERMISSION_CODE)
    }
    
    /**
     * Check if permission was permanently denied
     */
    fun isPermissionPermanentlyDenied(activity: Activity): Boolean {
        val permissions = getRequiredPermissions()
        return permissions.any { permission ->
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission) &&
            ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED
        }
    }
}
