package com.provideoplayer.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.provideoplayer.R
import com.provideoplayer.model.VideoItem

/**
 * RecyclerView adapter for displaying video list
 */
class VideoAdapter(
    private val onVideoClick: (VideoItem, Int) -> Unit,
    private val onVideoLongClick: (VideoItem) -> Boolean,
    private val onMenuClick: ((VideoItem, View) -> Unit)? = null
) : ListAdapter<VideoItem, VideoAdapter.VideoViewHolder>(VideoDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_GRID = 0
        private const val VIEW_TYPE_LIST = 1
        
        /**
         * Check if a URI exists in the history JSON array
         */
        fun isUriInHistory(historyJson: String, uri: String): Boolean {
            return try {
                val jsonArray = org.json.JSONArray(historyJson)
                for (i in 0 until jsonArray.length()) {
                    if (jsonArray.getString(i) == uri) {
                        return true
                    }
                }
                false
            } catch (e: Exception) {
                false
            }
        }
    }

    // Selection state
    val selectedItems = mutableSetOf<VideoItem>()
    
    fun isSelected(video: VideoItem): Boolean = selectedItems.contains(video)
    
    fun toggleSelection(video: VideoItem) {
        if (selectedItems.contains(video)) {
            selectedItems.remove(video)
        } else {
            selectedItems.add(video)
        }
        notifyDataSetChanged()
    }
    
    fun clearSelection() {
        selectedItems.clear()
        notifyDataSetChanged()
    }

    // Set to true for list view, false for grid view
    var isListView: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                // Force recreate all ViewHolders with new layout
                notifyDataSetChanged()
            }
        }

    override fun getItemViewType(position: Int): Int {
        return if (isListView) VIEW_TYPE_LIST else VIEW_TYPE_GRID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val layoutRes = if (viewType == VIEW_TYPE_LIST) R.layout.item_video_list else R.layout.item_video
        val view = LayoutInflater.from(parent.context)
            .inflate(layoutRes, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.videoThumbnail)
        private val title: TextView = itemView.findViewById(R.id.videoTitle)
        private val duration: TextView? = itemView.findViewById(R.id.videoDuration)
        private val size: TextView? = itemView.findViewById(R.id.videoSize)
        private val btnMenu: ImageView? = itemView.findViewById(R.id.btnMenu)
        private val newTag: TextView? = itemView.findViewById(R.id.newTag)
        private val checkboxSelected: ImageView? = itemView.findViewById(R.id.checkboxSelected)

        fun bind(video: VideoItem, position: Int) {
            title.text = video.title
            duration?.text = video.getFormattedDuration()
            
            // Check if it's an audio file
            val isAudio = video.mimeType.startsWith("audio") ||
                         video.path.endsWith(".mp3", true) ||
                         video.path.endsWith(".m4a", true) ||
                         video.path.endsWith(".flac", true) ||
                         video.path.endsWith(".wav", true) ||
                         video.path.endsWith(".aac", true)
            
            val prefs = itemView.context.getSharedPreferences("pro_video_player_prefs", android.content.Context.MODE_PRIVATE)
            
            // Check if media is in history using proper JSON parsing
            val videoUri = video.uri.toString()
            
            // Determine if video is new (not in history)
            val isNew = if (isAudio) {
                val audioHistoryJson = prefs.getString("audio_history", "[]") ?: "[]"
                !isUriInHistory(audioHistoryJson, videoUri)
            } else {
                val historyJson = prefs.getString("video_history", "[]") ?: "[]"
                !isUriInHistory(historyJson, videoUri)
            }
            
            // Show NEW tag in grid view
            newTag?.visibility = if (isNew) View.VISIBLE else View.GONE
            if (isAudio) {
                newTag?.setBackgroundColor(android.graphics.Color.parseColor("#00BFFF"))
            } else {
                newTag?.setBackgroundResource(R.drawable.bg_new_tag)
            }
            
            // Show selection checkbox
            checkboxSelected?.visibility = if (isSelected(video)) View.VISIBLE else View.GONE
            
            // Handle size view if present (list view only)
            size?.let { sizeView ->
                if (isAudio) {
                    val audioHistoryJson = prefs.getString("audio_history", "[]") ?: "[]"
                    val isListened = isUriInHistory(audioHistoryJson, videoUri)
                    
                    if (isListened) {
                        sizeView.text = video.getFormattedSize()
                        sizeView.setTextColor(itemView.context.getColor(R.color.text_secondary))
                        sizeView.setTypeface(null, android.graphics.Typeface.NORMAL)
                    } else {
                        sizeView.text = "● NEW"
                        sizeView.setTextColor(android.graphics.Color.parseColor("#00BFFF"))
                        sizeView.setTypeface(null, android.graphics.Typeface.BOLD)
                    }
                } else {
                    val historyJson = prefs.getString("video_history", "[]") ?: "[]"
                    val isWatched = isUriInHistory(historyJson, videoUri)
                    
                    if (isWatched) {
                        sizeView.text = video.getFormattedSize()
                        sizeView.setTextColor(itemView.context.getColor(R.color.text_secondary))
                        sizeView.setTypeface(null, android.graphics.Typeface.NORMAL)
                    } else {
                        sizeView.text = "● NEW"
                        sizeView.setTextColor(android.graphics.Color.parseColor("#00FF7F"))
                        sizeView.setTypeface(null, android.graphics.Typeface.BOLD)
                    }
                }
            }

            // Load thumbnail
            if (isAudio) {
                // Try to load audio thumbnail (album art), fallback to CD icon
                thumbnail.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                Glide.with(itemView.context)
                    .load(video.uri)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_audio_cd)
                    .error(R.drawable.ic_audio_cd)
                    .into(thumbnail)
            } else {
                // Load video thumbnail with Glide
                Glide.with(itemView.context)
                    .load(video.uri)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop()
                    .placeholder(R.drawable.ic_video_placeholder)
                    .error(R.drawable.ic_video_placeholder)
                    .into(thumbnail)
            }

            itemView.setOnClickListener {
                onVideoClick(video, position)
            }
            
            itemView.setOnLongClickListener {
                onVideoLongClick(video)
            }
            
            // Menu button click
            btnMenu?.setOnClickListener { view ->
                onMenuClick?.invoke(video, view)
            }
        }
    }

    class VideoDiffCallback : DiffUtil.ItemCallback<VideoItem>() {
        override fun areItemsTheSame(oldItem: VideoItem, newItem: VideoItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: VideoItem, newItem: VideoItem): Boolean {
            // Always return false to force rebind - this ensures NEW tag updates after history changes
            return false
        }
    }
}
