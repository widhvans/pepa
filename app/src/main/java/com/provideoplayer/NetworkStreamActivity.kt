package com.provideoplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.provideoplayer.databinding.ActivityNetworkStreamBinding
import org.json.JSONArray

class NetworkStreamActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNetworkStreamBinding
    private val streamHistory = mutableListOf<String>()
    private lateinit var historyAdapter: StreamHistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNetworkStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        setupUI()
        loadStreamHistory()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Network Stream"
        }
        binding.toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
    }

    private fun setupUI() {
        // Setup history RecyclerView
        historyAdapter = StreamHistoryAdapter(
            streamHistory,
            onItemClick = { url -> 
                binding.urlInput.setText(url)
            },
            onDeleteClick = { url ->
                removeFromHistory(url)
            }
        )
        binding.historyRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.historyRecyclerView.adapter = historyAdapter
        
        // Play button
        binding.btnPlay.setOnClickListener {
            val url = binding.urlInput.text.toString().trim()
            if (url.isNotEmpty()) {
                saveToStreamHistory(url)
                playNetworkStream(url, "Network Stream")
            } else {
                Toast.makeText(this, "Please enter a URL", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Clear button
        binding.btnClear.setOnClickListener {
            binding.urlInput.text?.clear()
        }
        
        // Paste button
        binding.btnPaste.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text.toString()
                binding.urlInput.setText(text)
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Protocol chips
        binding.chipHttp.setOnClickListener {
            if (!binding.urlInput.text.toString().startsWith("http")) {
                binding.urlInput.setText("http://")
                binding.urlInput.setSelection(binding.urlInput.text?.length ?: 0)
            }
        }
        
        binding.chipHttps.setOnClickListener {
            if (!binding.urlInput.text.toString().startsWith("https")) {
                binding.urlInput.setText("https://")
                binding.urlInput.setSelection(binding.urlInput.text?.length ?: 0)
            }
        }
        
        binding.chipRtsp.setOnClickListener {
            if (!binding.urlInput.text.toString().startsWith("rtsp")) {
                binding.urlInput.setText("rtsp://")
                binding.urlInput.setSelection(binding.urlInput.text?.length ?: 0)
            }
        }
        
        binding.chipRtmp.setOnClickListener {
            if (!binding.urlInput.text.toString().startsWith("rtmp")) {
                binding.urlInput.setText("rtmp://")
                binding.urlInput.setSelection(binding.urlInput.text?.length ?: 0)
            }
        }
    }
    
    private fun loadStreamHistory() {
        val prefs = getSharedPreferences("pro_video_player_prefs", MODE_PRIVATE)
        val historyJson = prefs.getString("stream_history", "[]") ?: "[]"
        
        try {
            val jsonArray = JSONArray(historyJson)
            streamHistory.clear()
            for (i in 0 until jsonArray.length()) {
                streamHistory.add(jsonArray.getString(i))
            }
            // Show most recent first
            streamHistory.reverse()
            updateHistoryVisibility()
            historyAdapter.notifyDataSetChanged()
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    private fun saveToStreamHistory(url: String) {
        val prefs = getSharedPreferences("pro_video_player_prefs", MODE_PRIVATE)
        val historyJson = prefs.getString("stream_history", "[]") ?: "[]"
        
        val historyArray = try {
            JSONArray(historyJson)
        } catch (e: Exception) {
            JSONArray()
        }
        
        // Remove if already exists
        val newArray = JSONArray()
        for (i in 0 until historyArray.length()) {
            val existingUrl = historyArray.getString(i)
            if (existingUrl != url) {
                newArray.put(existingUrl)
            }
        }
        
        // Add to end
        newArray.put(url)
        
        // Keep only last 10
        val finalArray = JSONArray()
        val startIndex = if (newArray.length() > 10) newArray.length() - 10 else 0
        for (i in startIndex until newArray.length()) {
            finalArray.put(newArray.getString(i))
        }
        
        prefs.edit().putString("stream_history", finalArray.toString()).apply()
        loadStreamHistory()
    }
    
    private fun removeFromHistory(url: String) {
        val prefs = getSharedPreferences("pro_video_player_prefs", MODE_PRIVATE)
        val historyJson = prefs.getString("stream_history", "[]") ?: "[]"
        
        val historyArray = try {
            JSONArray(historyJson)
        } catch (e: Exception) {
            JSONArray()
        }
        
        val newArray = JSONArray()
        for (i in 0 until historyArray.length()) {
            val existingUrl = historyArray.getString(i)
            if (existingUrl != url) {
                newArray.put(existingUrl)
            }
        }
        
        prefs.edit().putString("stream_history", newArray.toString()).apply()
        loadStreamHistory()
    }
    
    private fun updateHistoryVisibility() {
        if (streamHistory.isEmpty()) {
            binding.historyTitle.visibility = View.GONE
            binding.historyRecyclerView.visibility = View.GONE
        } else {
            binding.historyTitle.visibility = View.VISIBLE
            binding.historyRecyclerView.visibility = View.VISIBLE
        }
    }

    private fun playNetworkStream(url: String, title: String) {
        // Validate URL
        if (!isValidUrl(url)) {
            Toast.makeText(this, "Invalid URL format", Toast.LENGTH_SHORT).show()
            return
        }
        
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_URI, url)
            putExtra(PlayerActivity.EXTRA_VIDEO_TITLE, title)
            putExtra(PlayerActivity.EXTRA_IS_NETWORK_STREAM, true)
            putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, arrayListOf(url))
            putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST_TITLES, arrayListOf(title))
        }
        startActivity(intent)
    }

    private fun isValidUrl(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase()
            scheme in listOf("http", "https", "rtsp", "rtmp", "mms", "mmsh", "mmst")
        } catch (e: Exception) {
            false
        }
    }
    
    // Stream History Adapter
    inner class StreamHistoryAdapter(
        private val items: List<String>,
        private val onItemClick: (String) -> Unit,
        private val onDeleteClick: (String) -> Unit
    ) : RecyclerView.Adapter<StreamHistoryAdapter.ViewHolder>() {
        
        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val urlText: TextView = itemView.findViewById(R.id.streamUrl)
            val deleteBtn: ImageView = itemView.findViewById(R.id.btnDelete)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_stream_history, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val url = items[position]
            holder.urlText.text = url
            holder.itemView.setOnClickListener { onItemClick(url) }
            holder.deleteBtn.setOnClickListener { onDeleteClick(url) }
        }
        
        override fun getItemCount() = items.size
    }
}
