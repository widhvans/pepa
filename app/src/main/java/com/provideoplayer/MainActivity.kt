package com.provideoplayer

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.provideoplayer.adapter.FolderAdapter
import com.provideoplayer.adapter.VideoAdapter
import com.provideoplayer.databinding.ActivityMainBinding
import com.provideoplayer.model.FolderItem
import com.provideoplayer.model.VideoItem
import com.provideoplayer.utils.PermissionManager
import com.provideoplayer.utils.VideoScanner
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var folderAdapter: FolderAdapter
    
    private var allVideos: List<VideoItem> = emptyList()
    private var allAudioFiles: List<VideoItem> = emptyList()  // For Browse tab audio filter
    private var allFolders: List<FolderItem> = emptyList()
    private var currentFolderId: Long? = null
    private var isShowingFolders = true
    private var searchQuery: String = ""
    private var currentTab = 0  // 0=Videos, 1=Audio, 2=Browse, 3=Playlist
    private var browseFilter = 0  // 0=All, 1=Videos only, 2=Audio only
    
    // Scroll position state for preserving list position
    private var savedScrollPosition: android.os.Parcelable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved theme before calling super.onCreate and setContentView
        applyAppTheme()
        
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        setupRecyclerView()
        setupSwipeRefresh()
        setupBottomNavigation()
        setupFab()
        setupFilterButtons()
        
        checkPermissionAndLoadVideos()
    }
    
    private fun applyAppTheme() {
        val prefs = getSharedPreferences("pro_video_player_prefs", MODE_PRIVATE)
        val themeMode = prefs.getInt("theme_mode", 0)
        
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
        supportActionBar?.title = getString(R.string.app_name)
    }

    private fun setupRecyclerView() {
        // Video adapter
        videoAdapter = VideoAdapter(
            onVideoClick = { video, position ->
                openPlayer(video, position)
            },
            onVideoLongClick = { video ->
                showVideoInfo(video)
                true
            }
        )
        
        // Folder adapter
        folderAdapter = FolderAdapter { folder ->
            openFolder(folder)
        }
        
        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            adapter = folderAdapter
            setHasFixedSize(true)
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

    private fun setupFab() {
        binding.fabContinueVideo.setOnClickListener {
            continueLastVideo()
        }
    }
    
    private fun setupFilterButtons() {
        // Set default filter to Video (1)
        browseFilter = 1
        updateFilterButtonStyles()
        
        binding.btnFilterVideo.setOnClickListener {
            browseFilter = 1  // Select video filter
            updateFilterButtonStyles()
            
            // When filter changes, go back to folder list
            currentFolderId = null
            currentFolderPath = null
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
            supportActionBar?.title = "Browse"
            showBrowseMedia()
        }
        
        binding.btnFilterAudio.setOnClickListener {
            browseFilter = 2  // Select audio filter
            updateFilterButtonStyles()
            
            // When filter changes, go back to folder list
            currentFolderId = null
            currentFolderPath = null
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
            supportActionBar?.title = "Browse"
            showBrowseMedia()
        }
    }
    
    private fun updateFilterButtonStyles() {
        // Reset both buttons to outlined style
        binding.btnFilterVideo.strokeWidth = if (browseFilter == 1) 0 else 2
        binding.btnFilterAudio.strokeWidth = if (browseFilter == 2) 0 else 2
        
        // Set selected button background
        if (browseFilter == 1) {
            binding.btnFilterVideo.setBackgroundColor(getColor(R.color.purple_500))
            binding.btnFilterVideo.setTextColor(getColor(R.color.white))
            binding.btnFilterVideo.iconTint = android.content.res.ColorStateList.valueOf(getColor(R.color.white))
        } else {
            binding.btnFilterVideo.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            binding.btnFilterVideo.setTextColor(getColor(R.color.purple_500))
            binding.btnFilterVideo.iconTint = android.content.res.ColorStateList.valueOf(getColor(R.color.purple_500))
        }
        
        if (browseFilter == 2) {
            binding.btnFilterAudio.setBackgroundColor(getColor(R.color.purple_500))
            binding.btnFilterAudio.setTextColor(getColor(R.color.white))
            binding.btnFilterAudio.iconTint = android.content.res.ColorStateList.valueOf(getColor(R.color.white))
        } else {
            binding.btnFilterAudio.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            binding.btnFilterAudio.setTextColor(getColor(R.color.purple_500))
            binding.btnFilterAudio.iconTint = android.content.res.ColorStateList.valueOf(getColor(R.color.purple_500))
        }
    }
    
    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_videos -> {
                    currentTab = 0
                    supportActionBar?.title = "Videos"
                    binding.filterBar.visibility = View.GONE
                    binding.swipeRefresh.setPadding(0, 0, 0, 0)  // Reset padding
                    currentFolderId = null
                    currentFolderPath = null
                    loadVideos()
                    true
                }
                R.id.nav_audio -> {
                    currentTab = 1
                    supportActionBar?.title = "Audio"
                    binding.filterBar.visibility = View.GONE
                    binding.swipeRefresh.setPadding(0, 0, 0, 0)  // Reset padding
                    currentFolderId = null
                    currentFolderPath = null
                    showAudioFiles()
                    true
                }
                R.id.nav_browse -> {
                    currentTab = 2
                    supportActionBar?.title = "Browse"
                    supportActionBar?.subtitle = null  // Clear subtitle
                    supportActionBar?.setDisplayHomeAsUpEnabled(false)
                    currentFolderId = null
                    currentFolderPath = null
                    showBrowseMedia()
                    true
                }
                R.id.nav_playlist -> {
                    currentTab = 3
                    supportActionBar?.title = "Playlist"
                    supportActionBar?.subtitle = null  // Clear subtitle
                    binding.filterBar.visibility = View.GONE
                    binding.swipeRefresh.setPadding(0, 0, 0, 0)  // Reset padding
                    currentFolderId = null
                    currentFolderPath = null
                    showPlaylists()
                    true
                }
                R.id.nav_network -> {
                    currentTab = 4
                    supportActionBar?.subtitle = null  // Clear subtitle
                    openNetworkStreamDialog()
                    true
                }
                else -> false
            }
        }
    }
    
    private fun showAudioFiles() {
        // Show direct audio files (like Video tab)
        isShowingFolders = false
        binding.recyclerView.adapter = videoAdapter
        applyVideoLayoutPreference()
        
        // Scan audio files from MediaStore
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val audioFiles = VideoScanner.getAllAudio(this@MainActivity)
                binding.progressBar.visibility = View.GONE
                
                // Show total count in toolbar
                supportActionBar?.subtitle = "${audioFiles.size} audio files"
                
                if (audioFiles.isEmpty()) {
                    binding.recyclerView.visibility = View.GONE
                    binding.emptyView.visibility = View.VISIBLE
                    binding.emptyText.text = "No audio files found"
                } else {
                    binding.emptyView.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                    videoAdapter.submitList(audioFiles)
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.recyclerView.visibility = View.GONE
                binding.emptyView.visibility = View.VISIBLE
                binding.emptyText.text = "Error loading audio files"
            }
        }
    }
    
    private fun showPlaylists() {
        // Show recently watched videos as playlist
        isShowingFolders = false
        binding.recyclerView.adapter = videoAdapter
        applyVideoLayoutPreference()
        
        // Load history from prefs
        val prefs = getSharedPreferences("pro_video_player_prefs", MODE_PRIVATE)
        val historyJson = prefs.getString("video_history", "[]")
        
        try {
            val historyUris = org.json.JSONArray(historyJson)
            val historyVideos = mutableListOf<VideoItem>()
            
            for (i in 0 until historyUris.length()) {
                val uri = historyUris.getString(i)
                // Find matching video
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
            binding.recyclerView.visibility = View.GONE
            binding.emptyView.visibility = View.VISIBLE
            binding.emptyText.text = "Watch some videos to build your playlist!"
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        
        // Setup search
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = "Search videos..."
        
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let { searchVideos(it) }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                searchQuery = newText ?: ""
                if (searchQuery.isEmpty()) {
                    if (currentFolderId != null) {
                        showVideosInFolder(currentFolderId!!)
                    } else {
                        showFolders()
                    }
                } else {
                    searchVideos(searchQuery)
                }
                return true
            }
        })
        
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            R.id.action_sort_name -> {
                sortVideos(SortType.NAME)
                true
            }
            R.id.action_sort_date -> {
                sortVideos(SortType.DATE)
                true
            }
            R.id.action_sort_size -> {
                sortVideos(SortType.SIZE)
                true
            }
            R.id.action_sort_duration -> {
                sortVideos(SortType.DURATION)
                true
            }
            R.id.action_view_grid -> {
                setLayoutMode(true)
                true
            }
            R.id.action_view_list -> {
                setLayoutMode(false)
                true
            }
            R.id.action_settings -> {
                openSettings()
                true
            }
            R.id.action_history -> {
                openHistory()
                true
            }
            R.id.action_refresh -> {
                loadVideos()
                Toast.makeText(this, "Refreshing...", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            searchQuery.isNotEmpty() -> {
                // Clear search
                searchQuery = ""
                invalidateOptionsMenu()
                if (currentFolderId != null) {
                    showVideosInFolder(currentFolderId!!)
                } else {
                    showFolders()
                }
            }
            currentFolderId != null || currentFolderPath != null -> {
                // Go back to folder view
                currentFolderId = null
                currentFolderPath = null
                
                when (currentTab) {
                    0 -> showAllVideos()  // Videos tab
                    1 -> showAudioFiles()  // Audio tab - show audio folders
                    2 -> showBrowseMedia()  // Browse tab - show all folders
                    3 -> showPlaylists()  // Playlist tab
                    else -> showFolders()
                }
                
                supportActionBar?.apply {
                    setDisplayHomeAsUpEnabled(false)
                    title = when (currentTab) {
                        0 -> "Videos"
                        1 -> "Audio"
                        2 -> "Browse"
                        3 -> "Playlist"
                        else -> getString(R.string.app_name)
                    }
                }
            }
            else -> {
                super.onBackPressed()
            }
        }
    }

    private fun checkPermissionAndLoadVideos() {
        if (PermissionManager.hasStoragePermission(this)) {
            loadVideos()
        } else {
            showPermissionUI()
        }
    }

    private fun showPermissionUI() {
        binding.permissionLayout.visibility = View.VISIBLE
        binding.contentLayout.visibility = View.GONE
        
        binding.btnGrantPermission.setOnClickListener {
            PermissionManager.requestStoragePermission(this)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PermissionManager.STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                binding.permissionLayout.visibility = View.GONE
                binding.contentLayout.visibility = View.VISIBLE
                loadVideos()
            } else {
                if (PermissionManager.isPermissionPermanentlyDenied(this)) {
                    showSettingsDialog()
                } else {
                    Toast.makeText(this, "Permission required to access videos", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showSettingsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Permission Required")
            .setMessage("Storage permission is required to browse videos. Please enable it in app settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun loadVideos() {
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        
        lifecycleScope.launch {
            try {
                allVideos = VideoScanner.getAllVideos(this@MainActivity)
                allFolders = VideoScanner.getAllFolders(this@MainActivity)
                allAudioFiles = VideoScanner.getAllAudio(this@MainActivity)  // Load audio for Browse filter
                
                binding.swipeRefresh.isRefreshing = false
                binding.progressBar.visibility = View.GONE
                
                // Show content based on current tab
                when (currentTab) {
                    0 -> showAllVideos()  // Videos tab - show all videos
                    1 -> showAudioFiles()
                    2 -> showFolders()
                    3 -> showPlaylists()
                    else -> showAllVideos()
                }
            } catch (e: Exception) {
                binding.swipeRefresh.isRefreshing = false
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@MainActivity, "Error loading videos: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun showAllVideos() {
        isShowingFolders = false
        binding.recyclerView.adapter = videoAdapter
        applyVideoLayoutPreference()
        
        // Filter only video files (not audio)
        val videoFiles = allVideos.filter { 
            !it.mimeType.startsWith("audio") &&
            !it.path.endsWith(".mp3", true) &&
            !it.path.endsWith(".m4a", true) &&
            !it.path.endsWith(".aac", true) &&
            !it.path.endsWith(".wav", true) &&
            !it.path.endsWith(".flac", true)
        }
        
        // Show total count in toolbar
        supportActionBar?.subtitle = "${videoFiles.size} videos"
        
        if (videoFiles.isEmpty()) {
            binding.recyclerView.visibility = View.GONE
            binding.emptyView.visibility = View.VISIBLE
            binding.emptyText.text = "No videos found"
        } else {
            binding.emptyView.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            videoAdapter.submitList(videoFiles)
        }
    }

    private fun showFolders() {
        isShowingFolders = true
        binding.recyclerView.adapter = folderAdapter
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        
        if (allFolders.isEmpty()) {
            binding.recyclerView.visibility = View.GONE
            binding.emptyView.visibility = View.VISIBLE
            binding.emptyText.text = "No folders found"
        } else {
            binding.emptyView.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            folderAdapter.submitList(allFolders)
        }
    }

    private var currentFolderPath: String? = null  // For audio folders
    
    private fun openFolder(folder: FolderItem) {
        currentFolderId = folder.id
        currentFolderPath = folder.path
        
        if (currentTab == 1) {
            // Audio tab - show audio files from folder path
            showAudioInFolder(folder.path)
        } else if (currentTab == 2 && browseFilter == 2) {
            // Browse tab with Audio filter - show audio files from folder path
            showAudioInFolder(folder.path)
        } else {
            // Video/Browse tab - show videos by folder ID
            showVideosInFolder(folder.id)
        }
        
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = folder.name
        }
    }
    
    private fun showAudioInFolder(folderPath: String) {
        isShowingFolders = false
        binding.recyclerView.adapter = videoAdapter
        applyVideoLayoutPreference()
        
        // Load audio files from this folder only (not subdirectories)
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val allAudio = VideoScanner.getAllAudio(this@MainActivity)
                // Match exact folder path, not subdirectories
                val folderAudio = allAudio.filter { 
                    it.path.substringBeforeLast("/") == folderPath 
                }
                binding.progressBar.visibility = View.GONE
                
                videoAdapter.submitList(folderAudio)
                
                if (folderAudio.isEmpty()) {
                    binding.emptyView.visibility = View.VISIBLE
                    binding.emptyText.text = "No audio files in this folder"
                } else {
                    binding.emptyView.visibility = View.GONE
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.emptyView.visibility = View.VISIBLE
                binding.emptyText.text = "Error loading audio files"
            }
        }
    }

    private fun showVideosInFolder(folderId: Long) {
        isShowingFolders = false
        binding.recyclerView.adapter = videoAdapter
        applyVideoLayoutPreference()
        
        // Get folder files and apply browse filter if in Browse tab
        var folderVideos = allVideos.filter { it.folderId == folderId }
        
        // Apply filter based on browseFilter when in Browse tab
        if (currentTab == 2 && browseFilter > 0) {
            folderVideos = folderVideos.filter { video ->
                val isAudio = video.mimeType.startsWith("audio") ||
                             video.path.endsWith(".mp3", true) ||
                             video.path.endsWith(".m4a", true) ||
                             video.path.endsWith(".aac", true) ||
                             video.path.endsWith(".wav", true) ||
                             video.path.endsWith(".flac", true)
                             
                when (browseFilter) {
                    1 -> !isAudio  // Videos only
                    2 -> isAudio   // Audio only
                    else -> true
                }
            }
        }
        
        videoAdapter.submitList(folderVideos)
        
        if (folderVideos.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.emptyText.text = when (browseFilter) {
                1 -> "No videos in this folder"
                2 -> "No audio files in this folder"
                else -> "No media files found"
            }
        } else {
            binding.emptyView.visibility = View.GONE
        }
    }

    private fun searchVideos(query: String) {
        isShowingFolders = false
        binding.recyclerView.adapter = videoAdapter
        applyVideoLayoutPreference()
        
        val results = allVideos.filter { 
            it.title.contains(query, ignoreCase = true) ||
            it.folderName.contains(query, ignoreCase = true)
        }
        videoAdapter.submitList(results)
        
        if (results.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.emptyText.text = "No videos found for \"$query\""
        } else {
            binding.emptyView.visibility = View.GONE
        }
    }

    private fun sortVideos(sortType: SortType) {
        val currentList = if (isShowingFolders) {
            return // Can't sort folders view
        } else {
            videoAdapter.currentList.toList()
        }
        
        val sorted = when (sortType) {
            SortType.NAME -> currentList.sortedBy { it.title.lowercase() }
            SortType.DATE -> currentList.sortedByDescending { it.dateAdded }
            SortType.SIZE -> currentList.sortedByDescending { it.size }
            SortType.DURATION -> currentList.sortedByDescending { it.duration }
        }
        
        videoAdapter.submitList(sorted)
        Toast.makeText(this, "Sorted by ${sortType.name.lowercase()}", Toast.LENGTH_SHORT).show()
    }

    private fun setLayoutMode(isGrid: Boolean) {
        // Save current preference
        getSharedPreferences("pro_video_player_prefs", MODE_PRIVATE)
            .edit()
            .putBoolean("is_grid_view", isGrid)
            .apply()
        
        // Update adapter's view mode flag FIRST
        videoAdapter.isListView = !isGrid
        
        // Clear RecyclerView's cached views to prevent glitch
        binding.recyclerView.recycledViewPool.clear()
        
        // Update layout manager
        binding.recyclerView.layoutManager = if (isGrid) {
            GridLayoutManager(this, 2)
        } else {
            LinearLayoutManager(this)
        }
        
        // Re-set adapter to force complete recreation of all ViewHolders
        binding.recyclerView.adapter = null
        binding.recyclerView.adapter = videoAdapter
    }
    
    private fun applyVideoLayoutPreference() {
        val prefs = getSharedPreferences("pro_video_player_prefs", MODE_PRIVATE)
        val isGrid = prefs.getBoolean("is_grid_view", true)  // Default to grid
        
        // Apply layout manager based on saved preference
        binding.recyclerView.layoutManager = if (isGrid) {
            GridLayoutManager(this, 2)
        } else {
            LinearLayoutManager(this)
        }
        
        // Update adapter's view mode flag
        videoAdapter.isListView = !isGrid
    }

    private fun openPlayer(video: VideoItem, position: Int) {
        // Save to history immediately on click (so NEW tag disappears)
        val isAudio = video.mimeType.startsWith("audio") ||
                     video.path.endsWith(".mp3", true) ||
                     video.path.endsWith(".m4a", true) ||
                     video.path.endsWith(".flac", true) ||
                     video.path.endsWith(".wav", true) ||
                     video.path.endsWith(".aac", true)
        
        if (isAudio) {
            saveAudioToHistory(video.uri.toString())
        } else {
            saveVideoToHistory(video.uri.toString(), video.title)
        }
        
        // Get the correct playlist based on current context
        val playlist = if (isShowingFolders) {
            // If clicking from folders view (shouldn't happen normally)
            allVideos
        } else {
            // Get current displayed videos
            videoAdapter.currentList.toList()
        }
        
        // Find the correct index of clicked video in the playlist
        val videoIndex = playlist.indexOfFirst { it.id == video.id }.takeIf { it >= 0 } ?: position
        
        // Load saved playback position for this URI (for resume from history/playlist)
        val savedPosition = getSavedPlaybackPosition(video.uri.toString())
        
        val intent = Intent(this, PlayerActivity::class.java).apply {
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
    
    /**
     * Get saved playback position for a URI from stored positions
     */
    private fun getSavedPlaybackPosition(uri: String): Long {
        val prefs = getSharedPreferences("pro_video_player_prefs", MODE_PRIVATE)
        val positionsJson = prefs.getString("video_positions", "{}") ?: "{}"
        
        return try {
            val positionsObj = org.json.JSONObject(positionsJson)
            val uriKey = uri.hashCode().toString()
            positionsObj.optLong(uriKey, 0L)
        } catch (e: Exception) {
            0L
        }
    }
    
    private fun saveVideoToHistory(uri: String, title: String) {
        val prefs = getSharedPreferences("pro_video_player_prefs", MODE_PRIVATE)
        val historyJson = prefs.getString("video_history", "[]") ?: "[]"
        
        val historyArray = try {
            org.json.JSONArray(historyJson)
        } catch (e: Exception) {
            org.json.JSONArray()
        }
        
        // Remove if already exists (to move to end)
        val newArray = org.json.JSONArray()
        for (i in 0 until historyArray.length()) {
            val existingUri = historyArray.getString(i)
            if (existingUri != uri) {
                newArray.put(existingUri)
            }
        }
        
        // Add current video to end
        newArray.put(uri)
        
        // Keep only last 20 items
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
        val prefs = getSharedPreferences("pro_video_player_prefs", MODE_PRIVATE)
        val historyJson = prefs.getString("audio_history", "[]") ?: "[]"
        
        val historyArray = try {
            org.json.JSONArray(historyJson)
        } catch (e: Exception) {
            org.json.JSONArray()
        }
        
        // Remove if already exists (to move to end)
        val newArray = org.json.JSONArray()
        for (i in 0 until historyArray.length()) {
            val existingUri = historyArray.getString(i)
            if (existingUri != uri) {
                newArray.put(existingUri)
            }
        }
        
        // Add current audio to end
        newArray.put(uri)
        
        // Keep only last 50 items
        val finalArray = org.json.JSONArray()
        val startIndex = if (newArray.length() > 50) newArray.length() - 50 else 0
        for (i in startIndex until newArray.length()) {
            finalArray.put(newArray.getString(i))
        }
        
        prefs.edit()
            .putString("audio_history", finalArray.toString())
            .apply()
    }

    private fun showVideoInfo(video: VideoItem) {
        MaterialAlertDialogBuilder(this)
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
            .setNeutralButton("Delete") { _, _ ->
                confirmAndDeleteVideo(video)
            }
            .show()
    }
    
    private fun confirmAndDeleteVideo(video: VideoItem) {
        // Delete directly without confirmation
        deleteVideo(video)
    }
    
    private fun deleteVideo(video: VideoItem) {
        try {
            // Try to delete using ContentResolver
            val deletedRows = contentResolver.delete(video.uri, null, null)
            
            if (deletedRows > 0) {
                Toast.makeText(this, "Deleted: ${video.title}", Toast.LENGTH_SHORT).show()
                // Refresh the list
                loadVideos()
            } else {
                // If ContentResolver fails, try file deletion
                val file = java.io.File(video.path)
                if (file.exists() && file.delete()) {
                    // Also delete from MediaStore
                    contentResolver.delete(
                        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        "${android.provider.MediaStore.Video.Media.DATA}=?",
                        arrayOf(video.path)
                    )
                    Toast.makeText(this, "Deleted: ${video.title}", Toast.LENGTH_SHORT).show()
                    loadVideos()
                } else {
                    Toast.makeText(this, "Failed to delete file", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: SecurityException) {
            // On Android 10+ (Scoped Storage), we need special handling
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Request delete permission via SAF
                try {
                    val pendingIntent = android.provider.MediaStore.createDeleteRequest(
                        contentResolver,
                        listOf(video.uri)
                    )
                    startIntentSenderForResult(
                        pendingIntent.intentSender,
                        DELETE_REQUEST_CODE,
                        null, 0, 0, 0
                    )
                } catch (e2: Exception) {
                    Toast.makeText(this, "Cannot delete: Permission denied", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Cannot delete: Permission denied", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error deleting file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    companion object {
        private const val DELETE_REQUEST_CODE = 1001
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DELETE_REQUEST_CODE && resultCode == RESULT_OK) {
            Toast.makeText(this, "File deleted successfully", Toast.LENGTH_SHORT).show()
            loadVideos()
        }
    }

    private fun openNetworkStreamDialog() {
        val intent = Intent(this, NetworkStreamActivity::class.java)
        startActivity(intent)
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }
    
    private fun continueLastVideo() {
        val prefs = getSharedPreferences("pro_video_player_prefs", MODE_PRIVATE)
        val lastUri = prefs.getString("last_video_uri", null)
        val lastTitle = prefs.getString("last_video_title", "Video")
        val lastPosition = prefs.getLong("last_video_position", 0L)
        
        if (lastUri.isNullOrEmpty()) {
            Toast.makeText(this, "No video to continue. Start watching!", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Check if it's a network stream
        val isNetworkStream = lastUri.startsWith("http://") || lastUri.startsWith("https://")
        
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_URI, lastUri)
            putExtra(PlayerActivity.EXTRA_VIDEO_TITLE, lastTitle)
            putExtra(PlayerActivity.EXTRA_PLAYBACK_POSITION, lastPosition)  // Resume from saved position
            putExtra(PlayerActivity.EXTRA_IS_NETWORK_STREAM, isNetworkStream)
            putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, arrayListOf(lastUri))
            putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST_TITLES, arrayListOf(lastTitle ?: "Video"))
        }
        startActivity(intent)
    }
    
    private fun showBrowseMedia() {
        // Show filter bar for browse tab
        binding.filterBar.visibility = View.VISIBLE
        
        // Show folders that contain video or audio based on filter
        isShowingFolders = true
        binding.recyclerView.adapter = folderAdapter
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        
        // Add top padding to recyclerview to account for filter bar
        binding.swipeRefresh.setPadding(0, 56.dpToPx(), 0, 0)
        
        // Filter folders and recalculate counts based on browseFilter
        val filteredFolders = when (browseFilter) {
            1 -> {
                // Video filter - show folders with videos, count only videos
                allFolders.mapNotNull { folder ->
                    val videoCount = allVideos.count { video ->
                        video.folderId == folder.id &&
                        !video.mimeType.startsWith("audio") &&
                        !video.path.endsWith(".mp3", true) &&
                        !video.path.endsWith(".m4a", true) &&
                        !video.path.endsWith(".aac", true) &&
                        !video.path.endsWith(".wav", true) &&
                        !video.path.endsWith(".flac", true)
                    }
                    if (videoCount > 0) folder.copy(videoCount = videoCount) else null
                }
            }
            2 -> {
                // Audio filter - create folders from audio files by path
                val audioFolderMap = mutableMapOf<String, Int>()
                allAudioFiles.forEach { audio ->
                    val folderPath = audio.path.substringBeforeLast("/")
                    audioFolderMap[folderPath] = (audioFolderMap[folderPath] ?: 0) + 1
                }
                
                // Convert to FolderItem list
                audioFolderMap.map { (path, count) ->
                    val folderName = path.substringAfterLast("/")
                    FolderItem(
                        id = path.hashCode().toLong(),
                        name = if (folderName.isNotEmpty()) folderName else "Audio",
                        path = path,
                        videoCount = count
                    )
                }
            }
            else -> allFolders  // No filter
        }
        
        if (filteredFolders.isEmpty()) {
            binding.recyclerView.visibility = View.GONE
            binding.emptyView.visibility = View.VISIBLE
            binding.emptyText.text = when (browseFilter) {
                1 -> "No video folders found"
                2 -> "No audio folders found"
                else -> "No media folders found"
            }
        } else {
            binding.emptyView.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            // Set media type for proper label display
            folderAdapter.mediaType = browseFilter
            folderAdapter.submitList(filteredFolders.sortedByDescending { it.videoCount })
        }
    }
    
    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
    
    private fun openHistory() {
        val prefs = getSharedPreferences("pro_video_player_prefs", MODE_PRIVATE)
        val historyJson = prefs.getString("video_history", "[]")
        
        try {
            val historyUris = org.json.JSONArray(historyJson)
            
            if (historyUris.length() == 0) {
                Toast.makeText(this, "No history yet. Start watching!", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Build list of titles
            val historyItems = mutableListOf<Pair<String, String>>()
            for (i in historyUris.length() - 1 downTo 0) {
                val uri = historyUris.getString(i)
                val video = allVideos.find { it.uri.toString() == uri }
                val title = video?.title ?: uri.substringAfterLast("/")
                historyItems.add(title to uri)
            }
            
            val titles = historyItems.map { it.first }.toTypedArray()
            
            MaterialAlertDialogBuilder(this)
                .setTitle("Recently Watched")
                .setItems(titles) { _, which ->
                    val (title, uri) = historyItems[which]
                    
                    // Check if it's a network stream
                    val isNetworkStream = uri.startsWith("http://") || uri.startsWith("https://")
                    
                    // Load saved position for resume
                    val savedPosition = getSavedPlaybackPosition(uri)
                    
                    val intent = Intent(this, PlayerActivity::class.java).apply {
                        putExtra(PlayerActivity.EXTRA_VIDEO_URI, uri)
                        putExtra(PlayerActivity.EXTRA_VIDEO_TITLE, title)
                        putExtra(PlayerActivity.EXTRA_IS_NETWORK_STREAM, isNetworkStream)
                        if (savedPosition > 0L) {
                            putExtra(PlayerActivity.EXTRA_PLAYBACK_POSITION, savedPosition)
                        }
                        putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, arrayListOf(uri))
                        putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST_TITLES, arrayListOf(title))
                    }
                    startActivity(intent)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "No history yet. Start watching!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (PermissionManager.hasStoragePermission(this) && allVideos.isEmpty()) {
            loadVideos()
        } else {
            // Save scroll position before refreshing
            val scrollPosition = binding.recyclerView.layoutManager?.onSaveInstanceState()
            
            // Refresh adapter to update NEW tags after returning from player
            // This forces a rebind which re-checks history
            val currentList = videoAdapter.currentList.toList()
            videoAdapter.submitList(null)
            videoAdapter.submitList(currentList) {
                // Restore scroll position after list is updated
                scrollPosition?.let {
                    binding.recyclerView.layoutManager?.onRestoreInstanceState(it)
                }
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Save scroll position when leaving activity
        savedScrollPosition = binding.recyclerView.layoutManager?.onSaveInstanceState()
    }

    enum class SortType {
        NAME, DATE, SIZE, DURATION
    }
}
