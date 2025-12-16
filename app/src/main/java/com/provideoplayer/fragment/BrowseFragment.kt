package com.provideoplayer.fragment

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.provideoplayer.PlayerActivity
import com.provideoplayer.R
import com.provideoplayer.adapter.FolderAdapter
import com.provideoplayer.adapter.VideoAdapter
import com.provideoplayer.databinding.FragmentBrowseBinding
import com.provideoplayer.model.FolderItem
import com.provideoplayer.model.VideoItem
import com.provideoplayer.utils.VideoScanner
import kotlinx.coroutines.launch

class BrowseFragment : Fragment() {
    
    private var _binding: FragmentBrowseBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var folderAdapter: FolderAdapter
    
    private var allVideos: List<VideoItem> = emptyList()
    private var allAudioFiles: List<VideoItem> = emptyList()
    private var allFolders: List<FolderItem> = emptyList()
    
    // 0=All, 1=Videos, 2=Audio (now only used inside folders)
    private var inFolderFilter = 0
    private var isShowingFolders = true
    private var currentFolderId: Long? = null
    private var currentFolderPath: String? = null
    private var currentFolderName: String? = null
    
    // Current folder content (unfiltered)
    private var currentFolderMedia: List<VideoItem> = emptyList()
    private var searchQuery: String = ""
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBrowseBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSwipeRefresh()
        setupInlineFilterBar()
        setupSearchBar()
        loadData()
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
                showMediaMenu(video, view)
            }
        )
        
        folderAdapter = FolderAdapter { folder ->
            openFolder(folder)
        }
        
        videoAdapter.newTagColorType = 2 // Orange for Browse tab
        
        binding.recyclerView.apply {
            setHasFixedSize(true)
            itemAnimator = null
        }
        
        setupSelectionBar()
    }
    
    private fun setupSelectionBar() {
        binding.btnClearSelection.setOnClickListener {
            videoAdapter.clearSelection()
            updateSelectionBar()
        }
        
        binding.btnShareSelected.setOnClickListener {
            shareSelectedMedia()
        }
        
        binding.btnDeleteSelected.setOnClickListener {
            deleteSelectedMedia()
        }
    }
    
    private fun updateSelectionBar() {
        val count = videoAdapter.selectedItems.size
        if (count > 0) {
            binding.selectionBar.visibility = View.VISIBLE
            binding.selectionCount.text = "$count selected"
        } else {
            binding.selectionBar.visibility = View.GONE
        }
    }
    
    private fun shareSelectedMedia() {
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
            
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share ${selected.size} files"))
            
            videoAdapter.clearSelection()
            updateSelectionBar()
        } catch (e: Exception) {
            android.widget.Toast.makeText(requireContext(), "Error sharing files", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun deleteSelectedMedia() {
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
                android.widget.Toast.makeText(requireContext(), "$deleted files deleted", android.widget.Toast.LENGTH_SHORT).show()
                videoAdapter.clearSelection()
                updateSelectionBar()
                loadData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun toggleSelection(video: VideoItem) {
        videoAdapter.toggleSelection(video)
        updateSelectionBar()
    }
    
    private fun showMediaMenu(video: VideoItem, anchorView: View) {
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
                    showMediaInfo(video)
                    true
                }
                R.id.action_delete -> {
                    deleteMedia(video)
                    true
                }
                R.id.action_send -> {
                    shareMedia(video)
                    true
                }
                else -> false
            }
        }
        popup.show()
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
    
    private fun deleteMedia(video: VideoItem) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete File")
            .setMessage("Are you sure you want to delete '${video.title}'?")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    val file = java.io.File(video.path)
                    if (file.exists() && file.delete()) {
                        android.widget.Toast.makeText(requireContext(), "Deleted successfully", android.widget.Toast.LENGTH_SHORT).show()
                        loadData()
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
    
    private fun shareMedia(video: VideoItem) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                java.io.File(video.path)
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (isAudioFile(video)) "audio/*" else "video/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(requireContext(), "Error sharing: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(
            R.color.purple_500,
            R.color.purple_700,
            R.color.teal_200
        )
        binding.swipeRefresh.setOnRefreshListener {
            loadData()
        }
    }
    
    private fun setupInlineFilterBar() {
        // All filter (default)
        binding.btnFilterAll.setOnClickListener {
            if (inFolderFilter == 0) return@setOnClickListener
            inFolderFilter = 0
            updateFilterIconStyles()
            applyInFolderFilter()
        }
        
        // Video filter
        binding.btnFilterVideo.setOnClickListener {
            if (inFolderFilter == 1) return@setOnClickListener
            inFolderFilter = 1
            updateFilterIconStyles()
            applyInFolderFilter()
        }
        
        // Audio filter
        binding.btnFilterAudio.setOnClickListener {
            if (inFolderFilter == 2) return@setOnClickListener
            inFolderFilter = 2
            updateFilterIconStyles()
            applyInFolderFilter()
        }
        
        // Search button
        binding.btnSearch.setOnClickListener {
            showSearchBar()
        }
    }
    
    private fun setupSearchBar() {
        // Close search
        binding.btnCloseSearch.setOnClickListener {
            hideSearchBar()
        }
        
        // Clear search text
        binding.btnClearSearch.setOnClickListener {
            binding.searchEditText.text.clear()
        }
        
        // Search text change listener
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString() ?: ""
                binding.btnClearSearch.visibility = if (searchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                applyInFolderFilter()
            }
        })
        
        // Search action
        binding.searchEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
    }
    
    private fun showSearchBar() {
        binding.inlineFilterBar.visibility = View.GONE
        binding.searchBar.visibility = View.VISIBLE
        binding.searchEditText.requestFocus()
        showKeyboard()
    }
    
    private fun hideSearchBar() {
        searchQuery = ""
        binding.searchEditText.text.clear()
        binding.searchBar.visibility = View.GONE
        binding.inlineFilterBar.visibility = View.VISIBLE
        hideKeyboard()
        applyInFolderFilter()
    }
    
    private fun showKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.searchEditText, InputMethodManager.SHOW_IMPLICIT)
    }
    
    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
    }
    
    private fun updateFilterIconStyles() {
        val context = requireContext()
        val activeColor = ContextCompat.getColor(context, R.color.purple_500)
        val inactiveColor = ContextCompat.getColor(context, R.color.text_secondary)
        
        binding.btnFilterAll.imageTintList = android.content.res.ColorStateList.valueOf(
            if (inFolderFilter == 0) activeColor else inactiveColor
        )
        binding.btnFilterVideo.imageTintList = android.content.res.ColorStateList.valueOf(
            if (inFolderFilter == 1) activeColor else inactiveColor
        )
        binding.btnFilterAudio.imageTintList = android.content.res.ColorStateList.valueOf(
            if (inFolderFilter == 2) activeColor else inactiveColor
        )
    }
    
    private fun loadData() {
        if (!isAdded) return
        
        binding.progressBar.visibility = View.VISIBLE
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                allVideos = VideoScanner.getAllVideos(requireContext())
                allAudioFiles = VideoScanner.getAllAudio(requireContext())
                allFolders = VideoScanner.getAllFolders(requireContext())
                
                binding.swipeRefresh.isRefreshing = false
                binding.progressBar.visibility = View.GONE
                
                showAllFolders()
            } catch (e: Exception) {
                binding.swipeRefresh.isRefreshing = false
                binding.progressBar.visibility = View.GONE
                binding.emptyView.visibility = View.VISIBLE
                binding.emptyText.text = "Error loading data"
            }
        }
    }
    
    /**
     * Show all folders that contain any media (video or audio)
     */
    private fun showAllFolders() {
        isShowingFolders = true
        currentFolderId = null
        currentFolderPath = null
        currentFolderName = null
        inFolderFilter = 0  // Reset filter when going back to folders
        searchQuery = ""
        
        // Hide inline filter bar at folder level
        binding.inlineFilterBar.visibility = View.GONE
        binding.searchBar.visibility = View.GONE
        binding.swipeRefresh.setPadding(0, 0, 0, 0)
        
        binding.recyclerView.recycledViewPool.clear()
        binding.recyclerView.adapter = folderAdapter
        applyFolderLayoutPreference()
        
        // Combine video and audio folders
        val folderMediaCount = mutableMapOf<Long, Pair<String, Int>>()  // id -> (name, count)
        val folderPaths = mutableMapOf<Long, String>()  // id -> path
        
        // Count videos per folder
        allFolders.forEach { folder ->
            val videoCount = allVideos.count { it.folderId == folder.id }
            if (videoCount > 0) {
                folderMediaCount[folder.id] = folder.name to videoCount
                folderPaths[folder.id] = folder.path
            }
        }
        
        // Add audio folders (by path since they might not have same IDs)
        val audioFolderMap = mutableMapOf<String, Int>()
        allAudioFiles.forEach { audio ->
            val folderPath = audio.path.substringBeforeLast("/")
            audioFolderMap[folderPath] = (audioFolderMap[folderPath] ?: 0) + 1
        }
        
        // Merge audio counts into existing folders or add new ones
        audioFolderMap.forEach { (path, audioCount) ->
            val existingFolder = allFolders.find { it.path == path }
            if (existingFolder != null) {
                val existing = folderMediaCount[existingFolder.id]
                if (existing != null) {
                    folderMediaCount[existingFolder.id] = existing.first to (existing.second + audioCount)
                } else {
                    folderMediaCount[existingFolder.id] = existingFolder.name to audioCount
                    folderPaths[existingFolder.id] = existingFolder.path
                }
            } else {
                // Create a new folder entry for audio-only folders
                val folderId = path.hashCode().toLong()
                val folderName = path.substringAfterLast("/").ifEmpty { "Audio" }
                folderMediaCount[folderId] = folderName to audioCount
                folderPaths[folderId] = path
            }
        }
        
        // Convert to FolderItem list
        val allMediaFolders = folderMediaCount.map { (id, nameCount) ->
            FolderItem(
                id = id,
                name = nameCount.first,
                path = folderPaths[id] ?: "",
                videoCount = nameCount.second
            )
        }
        
        if (allMediaFolders.isEmpty()) {
            binding.recyclerView.visibility = View.GONE
            binding.emptyView.visibility = View.VISIBLE
            binding.emptyText.text = "No media folders found"
        } else {
            binding.emptyView.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            folderAdapter.mediaType = 0  // All media
            folderAdapter.submitList(allMediaFolders.sortedByDescending { it.videoCount })
        }
    }
    
    private fun openFolder(folder: FolderItem) {
        currentFolderId = folder.id
        currentFolderPath = folder.path
        currentFolderName = folder.name
        inFolderFilter = 0  // Start with All filter
        searchQuery = ""
        
        (activity as? VideosFragment.TabHost)?.setBackEnabled(true)
        (activity as? VideosFragment.TabHost)?.updateTitle(folder.name)
        
        // Show inline filter bar inside folders
        binding.inlineFilterBar.visibility = View.VISIBLE
        binding.searchBar.visibility = View.GONE
        binding.swipeRefresh.setPadding(0, 48.dpToPx(), 0, 0)
        updateFilterIconStyles()
        
        binding.recyclerView.recycledViewPool.clear()
        
        // Load all media in this folder (both video and audio)
        loadFolderMedia(folder)
    }
    
    private fun loadFolderMedia(folder: FolderItem) {
        isShowingFolders = false
        
        // Get videos from folder
        val folderVideos = allVideos.filter { it.folderId == folder.id }
        
        // Get audio from folder path
        val folderAudio = allAudioFiles.filter { 
            it.path.substringBeforeLast("/") == folder.path 
        }
        
        // Combine all media
        currentFolderMedia = folderVideos + folderAudio
        
        // Apply current filter
        applyInFolderFilter()
    }
    
    private fun applyInFolderFilter() {
        if (isShowingFolders) return
        
        binding.recyclerView.adapter = null
        binding.recyclerView.adapter = videoAdapter
        applyLayoutPreference()
        
        var filteredMedia = when (inFolderFilter) {
            1 -> currentFolderMedia.filter { !isAudioFile(it) }  // Videos only
            2 -> currentFolderMedia.filter { isAudioFile(it) }   // Audio only
            else -> currentFolderMedia  // All
        }
        
        // Apply search filter
        if (searchQuery.isNotEmpty()) {
            filteredMedia = filteredMedia.filter {
                it.title.contains(searchQuery, ignoreCase = true)
            }
        }
        
        videoAdapter.submitList(filteredMedia)
        
        if (filteredMedia.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.emptyText.text = when {
                searchQuery.isNotEmpty() -> "No results for \"$searchQuery\""
                inFolderFilter == 1 -> "No videos in this folder"
                inFolderFilter == 2 -> "No audio files in this folder"
                else -> "No media files in this folder"
            }
        } else {
            binding.emptyView.visibility = View.GONE
        }
    }
    
    private fun isAudioFile(item: VideoItem): Boolean {
        return item.mimeType.startsWith("audio") ||
               item.path.endsWith(".mp3", true) ||
               item.path.endsWith(".m4a", true) ||
               item.path.endsWith(".aac", true) ||
               item.path.endsWith(".wav", true) ||
               item.path.endsWith(".flac", true)
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
    
    private fun applyFolderLayoutPreference() {
        val prefs = requireContext().getSharedPreferences("pro_video_player_prefs", Context.MODE_PRIVATE)
        val isGrid = prefs.getBoolean("is_grid_view", true)
        
        binding.recyclerView.layoutManager = if (isGrid) {
            GridLayoutManager(requireContext(), 2)
        } else {
            LinearLayoutManager(requireContext())
        }
    }
    
    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
    
    private fun openPlayer(video: VideoItem, position: Int) {
        val context = requireContext()
        val isAudio = isAudioFile(video)
        
        if (isAudio) {
            saveAudioToHistory(video.uri.toString())
        } else {
            saveVideoToHistory(video.uri.toString(), video.title)
        }
        
        val playlist = videoAdapter.currentList.toList()
        val videoIndex = playlist.indexOfFirst { it.id == video.id }.takeIf { it >= 0 } ?: position
        
        val intent = Intent(context, PlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(PlayerActivity.EXTRA_VIDEO_URI, video.uri.toString())
            putExtra(PlayerActivity.EXTRA_VIDEO_TITLE, video.title)
            putExtra(PlayerActivity.EXTRA_VIDEO_POSITION, videoIndex)
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
    
    private fun showMediaInfo(video: VideoItem) {
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
    
    fun onBackPressed(): Boolean {
        // If search bar is open, close it first
        if (binding.searchBar.visibility == View.VISIBLE) {
            hideSearchBar()
            return true
        }
        
        // If inside a folder, go back to folder list
        if (!isShowingFolders) {
            (activity as? VideosFragment.TabHost)?.setBackEnabled(false)
            (activity as? VideosFragment.TabHost)?.updateTitle("Browse")
            showAllFolders()
            return true
        }
        
        return false
    }
    
    fun refreshData() {
        if (isAdded && _binding != null) {
            // Save scroll position before layout change
            val scrollState = binding.recyclerView.layoutManager?.onSaveInstanceState()
            
            // Change layout without clearing data
            if (isShowingFolders) {
                applyFolderLayoutPreference()
            } else {
                applyLayoutPreference()
            }
            
            // Restore scroll position after layout change
            scrollState?.let {
                binding.recyclerView.layoutManager?.onRestoreInstanceState(it)
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Restore UI state when returning to this tab (especially after tab switch)
        if (!isShowingFolders && currentFolderName != null) {
            // We're inside a folder, restore back button and title
            (activity as? VideosFragment.TabHost)?.setBackEnabled(true)
            (activity as? VideosFragment.TabHost)?.updateTitle(currentFolderName!!)
            
            // Ensure inline filter bar is visible
            binding.inlineFilterBar.visibility = View.VISIBLE
            binding.swipeRefresh.setPadding(0, 48.dpToPx(), 0, 0)
        } else {
            // At folder level
            (activity as? VideosFragment.TabHost)?.setBackEnabled(false)
            (activity as? VideosFragment.TabHost)?.updateTitle("Browse")
        }
        
        // Refresh adapter to update NEW tags after playback
        if (::videoAdapter.isInitialized) {
            videoAdapter.notifyDataSetChanged()
        }
    }
    
    fun filterBySearch(query: String) {
        if (!isAdded || _binding == null) return
        
        // Only filter when inside a folder (showing videos)
        if (!isShowingFolders && ::videoAdapter.isInitialized) {
            val filtered = if (query.isEmpty()) {
                currentFolderMedia
            } else {
                currentFolderMedia.filter { video ->
                    video.title.contains(query, ignoreCase = true)
                }
            }
            
            videoAdapter.submitList(filtered)
            
            if (filtered.isEmpty()) {
                binding.emptyView.visibility = View.VISIBLE
                binding.emptyText.text = if (query.isEmpty()) "No files found" else "No results for \"$query\""
            } else {
                binding.emptyView.visibility = View.GONE
            }
        }
    }
    
    fun sortBy(sortType: Int) {
        if (!isAdded || _binding == null) return
        
        // Only sort when inside a folder (showing videos)
        if (!isShowingFolders && ::videoAdapter.isInitialized) {
            val currentList = videoAdapter.currentList.toMutableList()
            val sorted = when (sortType) {
                0 -> currentList.sortedBy { it.title.lowercase() }  // Name
                1 -> currentList.sortedByDescending { it.dateAdded }  // Date
                2 -> currentList.sortedByDescending { it.size }  // Size
                3 -> currentList.sortedByDescending { it.duration }  // Duration
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
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
