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
import com.provideoplayer.databinding.FragmentAudioBinding
import com.provideoplayer.model.VideoItem
import com.provideoplayer.utils.VideoScanner
import kotlinx.coroutines.launch

class AudioFragment : Fragment() {
    
    private var _binding: FragmentAudioBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var videoAdapter: VideoAdapter
    private var allAudioFiles: List<VideoItem> = emptyList()
    private var currentFilter = "songs" // songs, albums, artists
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAudioBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSwipeRefresh()
        setupFilterChips()
        loadAudioFiles()
    }
    
    private fun setupFilterChips() {
        binding.chipSongs.setOnClickListener {
            currentFilter = "songs"
            applyFilter()
        }
        
        binding.chipAlbums.setOnClickListener {
            currentFilter = "albums"
            applyFilter()
        }
        
        binding.chipArtists.setOnClickListener {
            currentFilter = "artists"
            applyFilter()
        }
    }
    
    private fun applyFilter() {
        if (allAudioFiles.isEmpty()) return
        
        val filteredList = when (currentFilter) {
            "songs" -> allAudioFiles
            "albums" -> {
                // Group by folder (album)
                allAudioFiles.distinctBy { it.folderName }.sortedBy { it.folderName }
            }
            "artists" -> {
                // Group by parent folder (artist)
                allAudioFiles.distinctBy { 
                    it.path.substringBeforeLast("/").substringBeforeLast("/")
                }.sortedBy { it.title }
            }
            else -> allAudioFiles
        }
        
        videoAdapter.submitList(filteredList)
        
        val label = when (currentFilter) {
            "songs" -> "${filteredList.size} songs"
            "albums" -> "${filteredList.size} albums"
            "artists" -> "${filteredList.size} artists"
            else -> "${filteredList.size} items"
        }
        (activity as? VideosFragment.TabHost)?.updateSubtitle(label)
    }
    
    private fun setupRecyclerView() {
        videoAdapter = VideoAdapter(
            onVideoClick = { audio, position ->
                // If in selection mode, toggle selection, otherwise play
                if (videoAdapter.selectedItems.isNotEmpty()) {
                    toggleSelection(audio)
                } else {
                    openPlayer(audio, position)
                }
            },
            onVideoLongClick = { audio ->
                // Long press to start/toggle selection
                toggleSelection(audio)
                true
            },
            onMenuClick = { audio, view ->
                showAudioMenu(audio, view)
            }
        )
        
        videoAdapter.newTagColorType = 1 // Cyan for Audio tab
        
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
            shareSelectedAudios()
        }
        
        binding.btnDeleteSelected.setOnClickListener {
            deleteSelectedAudios()
        }
    }
    
    private fun updateSelectionBar() {
        val count = videoAdapter.selectedItems.size
        if (count > 0) {
            binding.selectionBar.visibility = android.view.View.VISIBLE
            binding.selectionCount.text = "$count selected"
        } else {
            binding.selectionBar.visibility = android.view.View.GONE
        }
    }
    
    private fun shareSelectedAudios() {
        val selected = videoAdapter.selectedItems.toList()
        if (selected.isEmpty()) return
        
        try {
            val uris = selected.map { audio ->
                androidx.core.content.FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    java.io.File(audio.path)
                )
            }
            
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                type = "audio/*"
                putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "Share ${selected.size} files"))
            
            videoAdapter.clearSelection()
            updateSelectionBar()
        } catch (e: Exception) {
            android.widget.Toast.makeText(requireContext(), "Error sharing files", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun deleteSelectedAudios() {
        val selected = videoAdapter.selectedItems.toList()
        if (selected.isEmpty()) return
        
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete ${selected.size} files?")
            .setMessage("This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                var deleted = 0
                selected.forEach { audio ->
                    try {
                        val file = java.io.File(audio.path)
                        if (file.exists() && file.delete()) {
                            deleted++
                        }
                    } catch (e: Exception) { }
                }
                android.widget.Toast.makeText(requireContext(), "$deleted files deleted", android.widget.Toast.LENGTH_SHORT).show()
                videoAdapter.clearSelection()
                updateSelectionBar()
                loadAudioFiles()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun toggleSelection(audio: VideoItem) {
        videoAdapter.toggleSelection(audio)
        updateSelectionBar()
    }
    
    private fun showAudioMenu(audio: VideoItem, anchorView: android.view.View) {
        val popup = android.widget.PopupMenu(requireContext(), anchorView)
        popup.menuInflater.inflate(R.menu.menu_video_item, popup.menu)
        
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_play -> {
                    openPlayer(audio, 0)
                    true
                }
                R.id.action_pip -> {
                    openPlayerInPiP(audio)
                    true
                }
                R.id.action_select -> {
                    toggleSelection(audio)
                    true
                }
                R.id.action_info -> {
                    showAudioInfo(audio)
                    true
                }
                R.id.action_delete -> {
                    deleteAudio(audio)
                    true
                }
                R.id.action_send -> {
                    shareAudio(audio)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }
    
    private fun openPlayerInPiP(audio: VideoItem) {
        val intent = android.content.Intent(requireContext(), PlayerActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(PlayerActivity.EXTRA_VIDEO_URI, audio.uri.toString())
            putExtra(PlayerActivity.EXTRA_VIDEO_TITLE, audio.title)
            putExtra("START_IN_PIP", true)
        }
        startActivity(intent)
    }
    
    private fun deleteAudio(audio: VideoItem) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Audio")
            .setMessage("Are you sure you want to delete '${audio.title}'?")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    val file = java.io.File(audio.path)
                    if (file.exists() && file.delete()) {
                        android.widget.Toast.makeText(requireContext(), "Audio deleted", android.widget.Toast.LENGTH_SHORT).show()
                        loadAudioFiles()
                    } else {
                        android.widget.Toast.makeText(requireContext(), "Failed to delete", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(requireContext(), "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun shareAudio(audio: VideoItem) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                java.io.File(audio.path)
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "Share Audio"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(requireContext(), "Cannot share this file", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(
            R.color.purple_500,
            R.color.purple_700,
            R.color.teal_200
        )
        binding.swipeRefresh.setOnRefreshListener {
            loadAudioFiles()
        }
    }
    
    private fun applyLayoutPreference() {
        val prefs = requireContext().getSharedPreferences("pro_video_player_prefs", Context.MODE_PRIVATE)
        val isGrid = prefs.getBoolean("is_grid_view", true)
        
        binding.recyclerView.layoutManager = if (isGrid) {
            GridLayoutManager(requireContext(), 2)
        } else {
            LinearLayoutManager(requireContext())
        }
        videoAdapter.isListView = !isGrid
    }
    
    fun loadAudioFiles() {
        if (!isAdded) return
        
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                allAudioFiles = VideoScanner.getAllAudio(requireContext())
                binding.swipeRefresh.isRefreshing = false
                binding.progressBar.visibility = View.GONE
                
                (activity as? VideosFragment.TabHost)?.updateSubtitle("${allAudioFiles.size} audio files")
                
                if (allAudioFiles.isEmpty()) {
                    binding.recyclerView.visibility = View.GONE
                    binding.emptyView.visibility = View.VISIBLE
                    binding.emptyText.text = "No audio files found"
                } else {
                    binding.emptyView.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                    videoAdapter.submitList(allAudioFiles)
                }
            } catch (e: Exception) {
                binding.swipeRefresh.isRefreshing = false
                binding.progressBar.visibility = View.GONE
                binding.recyclerView.visibility = View.GONE
                binding.emptyView.visibility = View.VISIBLE
                binding.emptyText.text = "Error loading audio files"
            }
        }
    }
    
    private fun openPlayer(audio: VideoItem, position: Int) {
        val context = requireContext()
        
        // Save to audio history
        saveAudioToHistory(audio.uri.toString())
        
        val playlist = videoAdapter.currentList.toList()
        val audioIndex = playlist.indexOfFirst { it.id == audio.id }.takeIf { it >= 0 } ?: position
        
        val intent = Intent(context, PlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(PlayerActivity.EXTRA_VIDEO_URI, audio.uri.toString())
            putExtra(PlayerActivity.EXTRA_VIDEO_TITLE, audio.title)
            putExtra(PlayerActivity.EXTRA_VIDEO_POSITION, audioIndex)
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
    
    private fun saveAudioToHistory(uri: String) {
        val prefs = requireContext().getSharedPreferences("pro_video_player_prefs", Context.MODE_PRIVATE)
        val historyJson = prefs.getString("audio_history", "[]") ?: "[]"
        
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
        val startIndex = if (newArray.length() > 50) newArray.length() - 50 else 0
        for (i in startIndex until newArray.length()) {
            finalArray.put(newArray.getString(i))
        }
        
        prefs.edit()
            .putString("audio_history", finalArray.toString())
            .apply()
    }
    
    private fun showAudioInfo(audio: VideoItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_video_info, null)
        
        // Set thumbnail (for audio, use CD icon or album art)
        val thumbnail = dialogView.findViewById<android.widget.ImageView>(R.id.infoThumbnail)
        com.bumptech.glide.Glide.with(this)
            .load(audio.uri)
            .placeholder(R.drawable.ic_audio_cd)
            .error(R.drawable.ic_audio_cd)
            .centerCrop()
            .into(thumbnail)
        
        // Set title
        dialogView.findViewById<android.widget.TextView>(R.id.infoTitle).text = audio.title
        
        // Set duration
        dialogView.findViewById<android.widget.TextView>(R.id.infoDuration).text = audio.getFormattedDuration()
        
        // Hide quality badge for audio
        dialogView.findViewById<android.widget.TextView>(R.id.infoQuality).visibility = View.GONE
        
        // Set size
        dialogView.findViewById<android.widget.TextView>(R.id.infoSize).text = audio.getFormattedSize()
        
        // Set resolution (show as Audio for audio files)
        dialogView.findViewById<android.widget.TextView>(R.id.infoResolution).text = "Audio"
        
        // Set date
        dialogView.findViewById<android.widget.TextView>(R.id.infoDate).text = audio.getFormattedDate()
        
        // Set path
        dialogView.findViewById<android.widget.TextView>(R.id.infoPath).text = audio.path
        
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setPositiveButton("Play") { _, _ ->
                openPlayer(audio, 0)
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
        // Refresh adapter to update NEW tags after playback
        if (::videoAdapter.isInitialized) {
            videoAdapter.notifyDataSetChanged()
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
