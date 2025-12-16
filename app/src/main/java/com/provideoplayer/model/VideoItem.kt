package com.provideoplayer.model

import android.net.Uri

/**
 * Data class representing a video file
 */
data class VideoItem(
    val id: Long,
    val title: String,
    val path: String,
    val uri: Uri,
    val duration: Long,
    val size: Long,
    val resolution: String,
    val dateAdded: Long,
    val folderName: String,
    val folderId: Long,
    val mimeType: String
) {
    fun getFormattedDuration(): String {
        val hours = duration / 3600000
        val minutes = (duration % 3600000) / 60000
        val seconds = (duration % 60000) / 1000
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
    
    fun getFormattedSize(): String {
        return when {
            size >= 1073741824 -> String.format("%.2f GB", size / 1073741824.0)
            size >= 1048576 -> String.format("%.2f MB", size / 1048576.0)
            size >= 1024 -> String.format("%.2f KB", size / 1024.0)
            else -> "$size B"
        }
    }
    
    fun getQualityBadge(): String {
        // Parse resolution like "1920x1080" or height directly
        val height = try {
            if (resolution.contains("x")) {
                resolution.split("x").getOrNull(1)?.toIntOrNull() ?: 0
            } else {
                resolution.toIntOrNull() ?: 0
            }
        } catch (e: Exception) { 0 }
        
        return when {
            height >= 2160 -> "2160p"
            height >= 1080 -> "1080p"
            height >= 720 -> "720p"
            height >= 480 -> "480p"
            height >= 360 -> "360p"
            height >= 240 -> "240p"
            height >= 144 -> "144p"
            height > 0 -> "${height}p"
            else -> ""
        }
    }
    
    fun getFormattedDate(): String {
        val sdf = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(dateAdded * 1000))
    }
}

/**
 * Data class representing a folder containing videos
 */
data class FolderItem(
    val id: Long,
    val name: String,
    val path: String,
    val videoCount: Int
)

/**
 * Enum for video filter types
 */
enum class VideoFilter(val displayName: String) {
    NONE("Normal"),
    GRAYSCALE("Grayscale"),
    SEPIA("Sepia"),
    NEGATIVE("Negative"),
    BRIGHTNESS("Bright"),
    CONTRAST("High Contrast"),
    SATURATION("Vivid"),
    SHARPEN("Sharp"),
    VIGNETTE("Vignette"),
    WARM("Warm"),
    COOL("Cool")
}

/**
 * Enum for aspect ratio modes
 */
enum class AspectRatioMode(val displayName: String) {
    FIT("Fit"),
    FILL("Fill"),
    STRETCH("Stretch"),
    RATIO_16_9("16:9"),
    RATIO_4_3("4:3"),
    RATIO_21_9("21:9")
}
