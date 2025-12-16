package com.provideoplayer.fragment

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.provideoplayer.PlayerActivity
import com.provideoplayer.R
import com.provideoplayer.adapter.VideoAdapter
import com.provideoplayer.databinding.FragmentPlaylistBinding
import com.provideoplayer.model.VideoItem
import com.provideoplayer.utils.VideoScanner
import kotlinx.coroutines.launch

class PlaylistFragment : Fragment() {
    
    private var _binding: FragmentPlaylistBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var videoAdapter: VideoAdapter
    private var allVideos: List<VideoItem> = emptyList()
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaylistBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSwipeRefresh()
        loadPlaylist()
    }
    
    private fun setupRecyclerView() {
        videoAdapter = VideoAdapter(
            onVideoClick = { video, position ->
                openPlayer(video, position)
            },
            onVideoLongClick = { video ->
                showVideoInfo(video)
                true
            }
        )
        
        binding.recyclerView.apply {
            adapter = videoAdapter
            setHasFixedSize(true)
        }
        applyLayoutPreference()
    }
    
    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(
            R.color.purple_500,
            R.color.purple_700,
            R.color.teal_200
        )
        binding.swipeRefresh.setOnRefreshListener {
            loadPlaylist()
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
    
    fun loadPlaylist() {
        if (!isAdded) return
        
        binding.progressBar.visibility = View.VISIBLE
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Load all videos first to match URIs
                if (allVideos.isEmpty()) {
                    allVideos = VideoScanner.getAllVideos(requireContext())
                }
                
                binding.swipeRefresh.isRefreshing = false
                binding.progressBar.visibility = View.GONE
                
                // Load history from prefs
                val prefs = requireContext().getSharedPreferences("pro_video_player_prefs", Context.MODE_PRIVATE)
                val historyJson = prefs.getString("video_history", "[]")
                
                val historyUris = org.json.JSONArray(historyJson)
                val historyVideos = mutableListOf<VideoItem>()
                
                for (i in 0 until historyUris.length()) {
                    val uri = historyUris.getString(i)
                    allVideos.find { it.uri.toString() == uri }?.let { historyVideos.add(it) }
                }
                
                if (historyVideos.isEmpty()) {
                    binding.recyclerView.visibility = View.GONE
                    binding.emptyView.visibility = View.VISIBLE
                    binding.emptyText.text = "Watch some videos to build your playlist!"
                } else {
                    binding.emptyView.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                    videoAdapter.submitList(historyVideos.reversed()) // Recent first
                }
            } catch (e: Exception) {
                binding.swipeRefresh.isRefreshing = false
                binding.progressBar.visibility = View.GONE
                binding.recyclerView.visibility = View.GONE
                binding.emptyView.visibility = View.VISIBLE
                binding.emptyText.text = "Watch some videos to build your playlist!"
            }
        }
    }
    
    private fun openPlayer(video: VideoItem, position: Int) {
        val context = requireContext()
        
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
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(video.title)
            .setMessage("""
                📁 Folder: ${video.folderName}
                ⏱️ Duration: ${video.getFormattedDuration()}
                📊 Size: ${video.getFormattedSize()}
                🎬 Resolution: ${video.resolution.ifEmpty { "Unknown" }}
                📂 Path: ${video.path}
            """.trimIndent())
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
        // Reload playlist on resume to get latest history
        if (_binding != null) {
            loadPlaylist()
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
