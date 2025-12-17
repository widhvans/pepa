package com.provideoplayer.fragment

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.provideoplayer.PlayerActivity
import com.provideoplayer.R
import com.provideoplayer.adapter.VideoAdapter
import com.provideoplayer.databinding.FragmentVideosBinding
import com.provideoplayer.model.VideoItem
import com.provideoplayer.utils.VideoScanner
import kotlinx.coroutines.launch

class VideosFragment : Fragment() {
    
    private var _binding: FragmentVideosBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var videoAdapter: VideoAdapter
    private var allVideos: List<VideoItem> = emptyList()
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVideosBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSwipeRefresh()
        loadVideos()
    }
    
    private fun setupRecyclerView() {
        videoAdapter = VideoAdapter(
            onVideoClick = { video, position ->
                // If in selection mode, toggle selection, otherwise play
                if (videoAdapter.selectedItems.isNotEmpty()) {
                    toggleSelection(video)
                } else {
                    openPlayer(video, position)
                }
            },
            onVideoLongClick = { video ->
                // Long press to start/toggle selection
                toggleSelection(video)
                true
            },
            onMenuClick = { video, view ->
                showVideoMenu(video, view)
            }
        )
        
        videoAdapter.newTagColorType = 0 // Green for Videos tab
        
        binding.recyclerView.apply {
            adapter = videoAdapter
            setHasFixedSize(true)
        }
        applyLayoutPreference()
        setupSelectionBar()
    }
    
    private fun setupSelectionBar() {
        binding.btnClearSelection.setOnClickListener {
            videoAdapter.clearSelection()
            updateSelectionBar()
        }
        
        binding.btnShareSelected.setOnClickListener {
            shareSelectedVideos()
        }
        
        binding.btnDeleteSelected.setOnClickListener {
            deleteSelectedVideos()
        }
    }
    
    private fun updateSelectionBar() {
        val count = videoAdapter.selectedItems.size
        val mainFab = activity?.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(com.provideoplayer.R.id.fabContinueVideo)
        
        if (count > 0) {
            binding.selectionBar.visibility = View.VISIBLE
            binding.selectionCount.text = "$count selected"
            // Animate FAB up to avoid overlap with selection bar (move above bar)
            mainFab?.animate()?.translationY(-160f)?.setDuration(200)?.start()
        } else {
            binding.selectionBar.visibility = View.GONE
            // Animate FAB back to original position
            mainFab?.animate()?.translationY(0f)?.setDuration(200)?.start()
        }
    }
    
    private fun shareSelectedVideos() {
        val selected = videoAdapter.selectedItems.toList()
        if (selected.isEmpty()) return
        
        try {
            val uris = selected.map { video ->
                androidx.core.content.FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    java.io.File(video.path)
                )
            }
            
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                type = "video/*"
                putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "Share ${selected.size} files"))
            
            videoAdapter.clearSelection()
            updateSelectionBar()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error sharing files", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun deleteSelectedVideos() {
        val selected = videoAdapter.selectedItems.toList()
        if (selected.isEmpty()) return
        
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete ${selected.size} files?")
            .setMessage("This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                var deleted = 0
                selected.forEach { video ->
                    try {
                        val file = java.io.File(video.path)
                        if (file.exists() && file.delete()) {
                            deleted++
                        }
                    } catch (e: Exception) { }
                }
                Toast.makeText(requireContext(), "$deleted files deleted", Toast.LENGTH_SHORT).show()
                videoAdapter.clearSelection()
                updateSelectionBar()
                loadVideos()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showVideoMenu(video: VideoItem, anchorView: View) {
        val popup = android.widget.PopupMenu(requireContext(), anchorView)
        popup.menuInflater.inflate(R.menu.menu_video_item, popup.menu)
        
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_play -> {
                    openPlayer(video, 0)
                    true
                }
                R.id.action_pip -> {
                    openPlayerInPiP(video)
                    true
                }
                R.id.action_select -> {
                    toggleSelection(video)
                    true
                }
                R.id.action_info -> {
                    showVideoInfo(video)
                    true
                }
                R.id.action_delete -> {
                    deleteVideo(video)
                    true
                }
                R.id.action_send -> {
                    shareVideo(video)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }
    
    private fun toggleSelection(video: VideoItem) {
        videoAdapter.toggleSelection(video)
        updateSelectionBar()
    }
    
    private fun openPlayerInPiP(video: VideoItem) {
        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(PlayerActivity.EXTRA_VIDEO_URI, video.uri.toString())
            putExtra(PlayerActivity.EXTRA_VIDEO_TITLE, video.title)
            putExtra("START_IN_PIP", true)
        }
        startActivity(intent)
    }
    
    private fun deleteVideo(video: VideoItem) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Video")
            .setMessage("Are you sure you want to delete '${video.title}'?")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    val file = java.io.File(video.path)
                    if (file.exists() && file.delete()) {
                        Toast.makeText(requireContext(), "Video deleted", Toast.LENGTH_SHORT).show()
                        loadVideos() // Refresh list
                    } else {
                        Toast.makeText(requireContext(), "Failed to delete", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun shareVideo(video: VideoItem) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                java.io.File(video.path)
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Video"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Cannot share this file", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(
            R.color.purple_500,
            R.color.purple_700,
            R.color.teal_200
        )
        binding.swipeRefresh.setOnRefreshListener {
            loadVideos()
        }
    }
    
    private fun applyLayoutPreference() {
        val prefs = requireContext().getSharedPreferences("pro_video_player_prefs", Context.MODE_PRIVATE)
        val isGrid = prefs.getBoolean("is_grid_view", false)
        
        binding.recyclerView.layoutManager = if (isGrid) {
            GridLayoutManager(requireContext(), 2)
        } else {
            LinearLayoutManager(requireContext())
        }
        videoAdapter.isListView = !isGrid
    }
    
    fun loadVideos() {
        if (!isAdded) return
        
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                allVideos = VideoScanner.getAllVideos(requireContext())
                binding.swipeRefresh.isRefreshing = false
                binding.progressBar.visibility = View.GONE
                
                // Filter only video files (not audio)
                val videoFiles = allVideos.filter { 
                    !it.mimeType.startsWith("audio") &&
                    !it.path.endsWith(".mp3", true) &&
                    !it.path.endsWith(".m4a", true) &&
                    !it.path.endsWith(".aac", true) &&
                    !it.path.endsWith(".wav", true) &&
                    !it.path.endsWith(".flac", true)
                }
                
                // Update toolbar subtitle via activity
                (activity as? TabHost)?.updateSubtitle("${videoFiles.size} videos")
                
                if (videoFiles.isEmpty()) {
                    binding.recyclerView.visibility = View.GONE
                    binding.emptyView.visibility = View.VISIBLE
                    binding.emptyText.text = "No videos found"
                } else {
                    binding.emptyView.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                    videoAdapter.submitList(videoFiles)
                }
            } catch (e: Exception) {
                binding.swipeRefresh.isRefreshing = false
                binding.progressBar.visibility = View.GONE
                binding.recyclerView.visibility = View.GONE
                binding.emptyView.visibility = View.VISIBLE
                binding.emptyText.text = "Error loading videos"
            }
        }
    }
    
    private fun openPlayer(video: VideoItem, position: Int) {
        val context = requireContext()
        
        // Save to history
        saveVideoToHistory(video.uri.toString(), video.title)
        
        val playlist = videoAdapter.currentList.toList()
        val videoIndex = playlist.indexOfFirst { it.id == video.id }.takeIf { it >= 0 } ?: position
        val savedPosition = getSavedPlaybackPosition(video.uri.toString())
        
        val intent = Intent(context, PlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(PlayerActivity.EXTRA_VIDEO_URI, video.uri.toString())
            putExtra(PlayerActivity.EXTRA_VIDEO_TITLE, video.title)
            putExtra(PlayerActivity.EXTRA_VIDEO_POSITION, videoIndex)
            if (savedPosition > 0L) {
                putExtra(PlayerActivity.EXTRA_PLAYBACK_POSITION, savedPosition)
            }
            putStringArrayListExtra(
                PlayerActivity.EXTRA_PLAYLIST,
                ArrayList(playlist.map { it.uri.toString() })
            )
            putStringArrayListExtra(
                PlayerActivity.EXTRA_PLAYLIST_TITLES,
                ArrayList(playlist.map { it.title })
            )
        }
        startActivity(intent)
    }
    
    private fun saveVideoToHistory(uri: String, title: String) {
        val prefs = requireContext().getSharedPreferences("pro_video_player_prefs", Context.MODE_PRIVATE)
        val historyJson = prefs.getString("video_history", "[]") ?: "[]"
        
        val historyArray = try {
            org.json.JSONArray(historyJson)
        } catch (e: Exception) {
            org.json.JSONArray()
        }
        
        val newArray = org.json.JSONArray()
        for (i in 0 until historyArray.length()) {
            val existingUri = historyArray.getString(i)
            if (existingUri != uri) {
                newArray.put(existingUri)
            }
        }
        newArray.put(uri)
        
        val finalArray = org.json.JSONArray()
        val startIndex = if (newArray.length() > 20) newArray.length() - 20 else 0
        for (i in startIndex until newArray.length()) {
            finalArray.put(newArray.getString(i))
        }
        
        prefs.edit()
            .putString("video_history", finalArray.toString())
            .putString("last_video_uri", uri)
            .putString("last_video_title", title)
            .apply()
    }
    
    private fun getSavedPlaybackPosition(uri: String): Long {
        val prefs = requireContext().getSharedPreferences("pro_video_player_prefs", Context.MODE_PRIVATE)
        val positionsJson = prefs.getString("video_positions", "{}") ?: "{}"
        
        return try {
            val positionsObj = org.json.JSONObject(positionsJson)
            val uriKey = uri.hashCode().toString()
            positionsObj.optLong(uriKey, 0L)
        } catch (e: Exception) {
            0L
        }
    }
    
    private fun showVideoInfo(video: VideoItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_video_info, null)
        
        // Set thumbnail
        val thumbnail = dialogView.findViewById<android.widget.ImageView>(R.id.infoThumbnail)
        com.bumptech.glide.Glide.with(this)
            .load(video.uri)
            .centerCrop()
            .into(thumbnail)
        
        // Set title
        dialogView.findViewById<android.widget.TextView>(R.id.infoTitle).text = video.title
        
        // Set duration
        dialogView.findViewById<android.widget.TextView>(R.id.infoDuration).text = video.getFormattedDuration()
        
        // Set quality
        val qualityBadge = video.getQualityBadge()
        val qualityView = dialogView.findViewById<android.widget.TextView>(R.id.infoQuality)
        if (qualityBadge.isNotEmpty()) {
            qualityView.text = qualityBadge
            qualityView.visibility = View.VISIBLE
        } else {
            qualityView.visibility = View.GONE
        }
        
        // Set size
        dialogView.findViewById<android.widget.TextView>(R.id.infoSize).text = video.getFormattedSize()
        
        // Set resolution
        dialogView.findViewById<android.widget.TextView>(R.id.infoResolution).text = video.resolution.ifEmpty { "Unknown" }
        
        // Set date
        dialogView.findViewById<android.widget.TextView>(R.id.infoDate).text = video.getFormattedDate()
        
        // Set path
        dialogView.findViewById<android.widget.TextView>(R.id.infoPath).text = video.path
        
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setPositiveButton("Play") { _, _ ->
                openPlayer(video, 0)
            }
            .setNegativeButton("Close", null)
            .show()
    }
    
    fun refreshData() {
        if (isAdded && _binding != null) {
            // Save scroll position before layout change
            val scrollState = binding.recyclerView.layoutManager?.onSaveInstanceState()
            
            // Change layout without reloading data
            applyLayoutPreference()
            
            // Restore scroll position after layout change
            scrollState?.let {
                binding.recyclerView.layoutManager?.onRestoreInstanceState(it)
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Refresh to update NEW tags without causing blink
        if (_binding != null && allVideos.isNotEmpty()) {
            // Just notify adapter of data changes without clearing list
            videoAdapter.notifyDataSetChanged()
        }
    }
    
    fun filterBySearch(query: String) {
        if (!isAdded || _binding == null) return
        
        val filtered = if (query.isEmpty()) {
            allVideos.filter { !it.mimeType.startsWith("audio") && !it.path.endsWith(".mp3", true) }
        } else {
            allVideos.filter { video ->
                !video.mimeType.startsWith("audio") && 
                !video.path.endsWith(".mp3", true) &&
                video.title.contains(query, ignoreCase = true)
            }
        }
        
        videoAdapter.submitList(filtered)
        
        if (filtered.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.emptyText.text = if (query.isEmpty()) "No videos found" else "No results for \"$query\""
        } else {
            binding.emptyView.visibility = View.GONE
        }
    }
    
    fun sortBy(sortType: Int) {
        if (!isAdded || _binding == null) return
        
        val currentList = videoAdapter.currentList.toMutableList()
        val sorted = when (sortType) {
            0 -> currentList.sortedBy { it.title.lowercase() }  // Name
            1 -> currentList.sortedByDescending { it.dateAdded }  // Date (newest first)
            2 -> currentList.sortedByDescending { it.size }  // Size (largest first)
            3 -> currentList.sortedByDescending { it.duration }  // Duration (longest first)
            else -> currentList
        }
        
        // Apply smooth animation
        val layoutAnimation = android.view.animation.AnimationUtils.loadLayoutAnimation(
            requireContext(), R.anim.layout_animation
        )
        binding.recyclerView.layoutAnimation = layoutAnimation
        
        videoAdapter.submitList(sorted) {
            binding.recyclerView.scheduleLayoutAnimation()
        }
    }
    
    fun getVideoCount(): Int {
        return videoAdapter.currentList.size
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    
    // Interface for communication with activity
    interface TabHost {
        fun updateSubtitle(subtitle: String?)
        fun updateTitle(title: String)
        fun setBackEnabled(enabled: Boolean)
    }
}
