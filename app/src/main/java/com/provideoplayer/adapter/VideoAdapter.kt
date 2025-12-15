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
    private val onVideoLongClick: (VideoItem) -> Boolean
) : ListAdapter<VideoItem, VideoAdapter.VideoViewHolder>(VideoDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.videoThumbnail)
        private val title: TextView = itemView.findViewById(R.id.videoTitle)
        private val duration: TextView = itemView.findViewById(R.id.videoDuration)
        private val size: TextView = itemView.findViewById(R.id.videoSize)
        private val resolution: TextView = itemView.findViewById(R.id.videoResolution)

        fun bind(video: VideoItem, position: Int) {
            title.text = video.title
            duration.text = video.getFormattedDuration()
            
            // Check if it's an audio file
            val isAudio = video.mimeType.startsWith("audio") ||
                         video.path.endsWith(".mp3", true) ||
                         video.path.endsWith(".m4a", true) ||
                         video.path.endsWith(".flac", true) ||
                         video.path.endsWith(".wav", true) ||
                         video.path.endsWith(".aac", true)
            
            val prefs = itemView.context.getSharedPreferences("pro_video_player_prefs", android.content.Context.MODE_PRIVATE)
            
            // Check if media is in history (separate history for audio and video)
            if (isAudio) {
                val audioHistoryJson = prefs.getString("audio_history", "[]") ?: "[]"
                val isListened = audioHistoryJson.contains(video.uri.toString())
                
                if (isListened) {
                    size.text = video.getFormattedSize()
                    size.setTextColor(itemView.context.getColor(R.color.text_secondary))
                    size.setTypeface(null, android.graphics.Typeface.NORMAL)
                } else {
                    size.text = "● NEW"
                    size.setTextColor(android.graphics.Color.parseColor("#00BFFF"))  // Deep sky blue for audio
                    size.setTypeface(null, android.graphics.Typeface.BOLD)
                }
            } else {
                val historyJson = prefs.getString("video_history", "[]") ?: "[]"
                val isWatched = historyJson.contains(video.uri.toString())
                
                if (isWatched) {
                    size.text = video.getFormattedSize()
                    size.setTextColor(itemView.context.getColor(R.color.text_secondary))
                    size.setTypeface(null, android.graphics.Typeface.NORMAL)
                } else {
                    size.text = "● NEW"
                    size.setTextColor(android.graphics.Color.parseColor("#00FF7F"))  // Bright green for video
                    size.setTypeface(null, android.graphics.Typeface.BOLD)
                }
            }
            
            // Show resolution if available
            if (video.resolution.isNotEmpty()) {
                resolution.visibility = View.VISIBLE
                resolution.text = video.resolution
            } else {
                resolution.visibility = View.GONE
            }

            // Load thumbnail
            if (isAudio) {
                // Use CD icon for audio files
                thumbnail.setImageResource(R.drawable.ic_audio_cd)
                thumbnail.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
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
        }
    }

    class VideoDiffCallback : DiffUtil.ItemCallback<VideoItem>() {
        override fun areItemsTheSame(oldItem: VideoItem, newItem: VideoItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: VideoItem, newItem: VideoItem): Boolean {
            return oldItem == newItem
        }
    }
}
