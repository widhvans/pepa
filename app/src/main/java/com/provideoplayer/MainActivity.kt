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
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.provideoplayer.adapter.MainPagerAdapter
import com.provideoplayer.databinding.ActivityMainBinding
import com.provideoplayer.fragment.BrowseFragment
import com.provideoplayer.fragment.VideosFragment
import com.provideoplayer.utils.PermissionManager

class MainActivity : AppCompatActivity(), VideosFragment.TabHost {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pagerAdapter: MainPagerAdapter
    
    private var currentTab = 0  // 0=Videos, 1=Audio, 2=Browse, 3=Playlist, 4=Network
    private var searchQuery: String = ""
    private var pendingNetworkClick = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved theme before calling super.onCreate and setContentView
        applyAppTheme()
        
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        setupViewPager()
        setupBottomNavigation()
        setupFab()
        
        checkPermissionAndLoadContent()
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

    private fun setupViewPager() {
        pagerAdapter = MainPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter
        
        // Disable swipe for network tab position (doesn't exist in pager)
        binding.viewPager.offscreenPageLimit = 2  // Keep adjacent fragments in memory
        
        // Listen to page changes
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                currentTab = position
                
                // Update bottom navigation
                val menuItemId = when (position) {
                    0 -> R.id.nav_videos
                    1 -> R.id.nav_audio
                    2 -> R.id.nav_browse
                    3 -> R.id.nav_playlist
                    else -> R.id.nav_videos
                }
                
                // Prevent triggering listener
                binding.bottomNavigation.setOnItemSelectedListener(null)
                binding.bottomNavigation.selectedItemId = menuItemId
                setupBottomNavigation()  // Re-attach listener
                
                // Update toolbar title
                supportActionBar?.title = when (position) {
                    0 -> "Videos"
                    1 -> "Audio"
                    2 -> "Browse"
                    3 -> "Playlist"
                    4 -> "Stream"
                    else -> getString(R.string.app_name)
                }
                
                // Clear subtitle when switching tabs
                if (position != 2) {  // Browse tab manages its own subtitle
                    supportActionBar?.subtitle = null
                    supportActionBar?.setDisplayHomeAsUpEnabled(false)
                }
            }
        })
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_videos -> {
                    binding.viewPager.setCurrentItem(0, true)
                    supportActionBar?.title = "Videos"
                    true
                }
                R.id.nav_audio -> {
                    binding.viewPager.setCurrentItem(1, true)
                    supportActionBar?.title = "Audio"
                    true
                }
                R.id.nav_browse -> {
                    binding.viewPager.setCurrentItem(2, true)
                    supportActionBar?.title = "Browse"
                    true
                }
                R.id.nav_playlist -> {
                    binding.viewPager.setCurrentItem(3, true)
                    supportActionBar?.title = "Playlist"
                    true
                }
                R.id.nav_network -> {
                    // Stream tab is now swipeable
                    binding.viewPager.setCurrentItem(4, true)
                    supportActionBar?.title = "Stream"
                    true
                }
                else -> false
            }
        }
    }

    private fun setupFab() {
        binding.fabContinueVideo.setOnClickListener {
            continueLastVideo()
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
                // TODO: Implement search across fragments
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                searchQuery = newText ?: ""
                // TODO: Implement search across fragments
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
                // TODO: Sort in current fragment
                Toast.makeText(this, "Sort by name", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_sort_date -> {
                Toast.makeText(this, "Sort by date", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_sort_size -> {
                Toast.makeText(this, "Sort by size", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_sort_duration -> {
                Toast.makeText(this, "Sort by duration", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_view_toggle -> {
                toggleLayoutMode()
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
                refreshCurrentFragment()
                Toast.makeText(this, "Refreshing...", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Check if Browse fragment can handle back
        if (currentTab == 2) {
            val browseFragment = pagerAdapter.getBrowseFragment()
            if (browseFragment?.onBackPressed() == true) {
                return
            }
        }
        
        if (searchQuery.isNotEmpty()) {
            searchQuery = ""
            invalidateOptionsMenu()
            return
        }
        
        super.onBackPressed()
    }

    private fun checkPermissionAndLoadContent() {
        if (PermissionManager.hasStoragePermission(this)) {
            binding.permissionLayout.visibility = View.GONE
            binding.contentLayout.visibility = View.VISIBLE
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
                // ViewPager will automatically load fragments which will load their data
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

    private fun toggleLayoutMode() {
        val prefs = getSharedPreferences("pro_video_player_prefs", MODE_PRIVATE)
        val isCurrentlyGrid = prefs.getBoolean("is_grid_view", true)
        
        prefs.edit()
            .putBoolean("is_grid_view", !isCurrentlyGrid)
            .apply()
        
        // Update toolbar icon
        invalidateOptionsMenu()
        
        // Refresh all fragments to apply new layout
        refreshCurrentFragment()
    }
    
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val prefs = getSharedPreferences("pro_video_player_prefs", MODE_PRIVATE)
        val isGrid = prefs.getBoolean("is_grid_view", true)
        
        menu.findItem(R.id.action_view_toggle)?.let { item ->
            item.setIcon(if (isGrid) R.drawable.ic_list_view else R.drawable.ic_grid_view)
        }
        
        return super.onPrepareOptionsMenu(menu)
    }

    private fun refreshCurrentFragment() {
        // Refresh ALL fragments to apply layout change across all tabs
        pagerAdapter.getVideosFragment()?.refreshData()
        pagerAdapter.getAudioFragment()?.refreshData()
        pagerAdapter.getBrowseFragment()?.refreshData()
        pagerAdapter.getPlaylistFragment()?.refreshData()
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
        
        val isNetworkStream = lastUri.startsWith("http://") || lastUri.startsWith("https://")
        
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_URI, lastUri)
            putExtra(PlayerActivity.EXTRA_VIDEO_TITLE, lastTitle)
            putExtra(PlayerActivity.EXTRA_PLAYBACK_POSITION, lastPosition)
            putExtra(PlayerActivity.EXTRA_IS_NETWORK_STREAM, isNetworkStream)
            putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, arrayListOf(lastUri))
            putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST_TITLES, arrayListOf(lastTitle ?: "Video"))
        }
        startActivity(intent)
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
            
            val historyItems = mutableListOf<Pair<String, String>>()
            for (i in historyUris.length() - 1 downTo 0) {
                val uri = historyUris.getString(i)
                val title = uri.substringAfterLast("/")
                historyItems.add(title to uri)
            }
            
            val titles = historyItems.map { it.first }.toTypedArray()
            
            MaterialAlertDialogBuilder(this)
                .setTitle("Recently Watched")
                .setItems(titles) { _, which ->
                    val (title, uri) = historyItems[which]
                    val isNetworkStream = uri.startsWith("http://") || uri.startsWith("https://")
                    
                    val intent = Intent(this, PlayerActivity::class.java).apply {
                        putExtra(PlayerActivity.EXTRA_VIDEO_URI, uri)
                        putExtra(PlayerActivity.EXTRA_VIDEO_TITLE, title)
                        putExtra(PlayerActivity.EXTRA_IS_NETWORK_STREAM, isNetworkStream)
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

    // TabHost interface implementation
    override fun updateSubtitle(subtitle: String?) {
        supportActionBar?.subtitle = subtitle
    }
    
    override fun updateTitle(title: String) {
        supportActionBar?.title = title
    }
    
    override fun setBackEnabled(enabled: Boolean) {
        supportActionBar?.setDisplayHomeAsUpEnabled(enabled)
    }
    
    override fun onResume() {
        super.onResume()
        if (PermissionManager.hasStoragePermission(this)) {
            binding.permissionLayout.visibility = View.GONE
            binding.contentLayout.visibility = View.VISIBLE
        }
    }

    companion object {
        private const val DELETE_REQUEST_CODE = 1001
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DELETE_REQUEST_CODE && resultCode == RESULT_OK) {
            Toast.makeText(this, "File deleted successfully", Toast.LENGTH_SHORT).show()
            refreshCurrentFragment()
        }
    }
}
