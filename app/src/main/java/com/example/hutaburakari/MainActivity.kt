package com.example.hutaburakari

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.hutaburakari.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), SearchView.OnQueryTextListener { // SearchView.OnQueryTextListener を実装

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var imageAdapter: ImageAdapter
    private var currentSelectedUrl: String? = null
    private var allItems: List<ImageItem> = emptyList() // 全アイテムを保持するリスト

    private val bookmarkActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        loadAndFetchInitialData()
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                Log.d("MainActivity", "Selected image URI: $uri")
                // MetadataExtractor.extract に context を渡すように変更
                val promptInfo = MetadataExtractor.extract(this@MainActivity, uri.toString())
                Log.d("MainActivity", "Extracted prompt info: $promptInfo")

                val intent = Intent(this@MainActivity, ImageDisplayActivity::class.java).apply {
                    putExtra(ImageDisplayActivity.EXTRA_IMAGE_URI, uri.toString())
                    putExtra(ImageDisplayActivity.EXTRA_PROMPT_INFO, promptInfo)
                }
                startActivity(intent)
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        setupRecyclerView()
        observeViewModel()
        setupClickListener()

        loadAndFetchInitialData()
    }

    private fun loadAndFetchInitialData() {
        currentSelectedUrl = BookmarkManager.getSelectedBookmarkUrl(this)
        binding.toolbar.subtitle = getCurrentBookmarkName()
        fetchDataForCurrentUrl()
    }

    private fun fetchDataForCurrentUrl() {
        currentSelectedUrl?.let {
            lifecycleScope.launch {
                applyCatalogSettings()
                viewModel.fetchImagesFromUrl(it)
            }
        } ?: run {
            showBookmarkSelectionDialog()
        }
    }

    private suspend fun applyCatalogSettings() {
        val settings = mapOf(
            "mode" to "catset",
            "cx" to "20",
            "cy" to "10",
            "cl" to "10"
        )
        try {
            NetworkClient.applySettings(this, settings)
            withContext(Dispatchers.Main) {
                // Toast.makeText(this@MainActivity, "カタログ設定を適用しました", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "設定の適用に失敗: ${e.message}", Toast.LENGTH_LONG).show()
            }
            e.printStackTrace()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.setOnQueryTextListener(this)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_reload -> {
                fetchDataForCurrentUrl()
                Toast.makeText(this, getString(R.string.reloading), Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_select_bookmark -> {
                showBookmarkSelectionDialog()
                true
            }
            R.id.action_manage_bookmarks -> {
                val intent = Intent(this, BookmarkActivity::class.java)
                bookmarkActivityResultLauncher.launch(intent)
                true
            }
            R.id.action_image_edit -> {
                val intent = Intent(this, ImagePickerActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_browse_local_images -> {
                pickImageLauncher.launch("image/*")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // SearchView.OnQueryTextListener の実装
    override fun onQueryTextSubmit(query: String?): Boolean {
        return false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        filterImages(newText)
        return true
    }

    private fun filterImages(query: String?) {
        val filteredList = if (query.isNullOrEmpty()) {
            allItems
        } else {
            allItems.filter { it.title.contains(query, ignoreCase = true) }
        }
        imageAdapter.submitList(filteredList)
    }


    private fun getCurrentBookmarkName(): String {
        val bookmarks = BookmarkManager.getBookmarks(this)
        return bookmarks.find { it.url == currentSelectedUrl }?.name ?: "ブックマーク未選択"
    }

    private fun showBookmarkSelectionDialog() {
        val bookmarks = BookmarkManager.getBookmarks(this)
        val bookmarkNames = bookmarks.map { it.name }.toTypedArray()

        if (bookmarkNames.isEmpty()) {
            Toast.makeText(this, "ブックマークがありません。まずはブックマークを登録してください。", Toast.LENGTH_LONG).show()
            val intent = Intent(this, BookmarkActivity::class.java)
            bookmarkActivityResultLauncher.launch(intent)
            return
        }

        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, bookmarkNames)

        AlertDialog.Builder(this)
            .setTitle("ブックマークを選択")
            .setAdapter(adapter) { dialog, which ->
                val selectedBookmark = bookmarks[which]
                currentSelectedUrl = selectedBookmark.url
                binding.toolbar.subtitle = selectedBookmark.name
                BookmarkManager.saveSelectedBookmarkUrl(this, selectedBookmark.url)
                fetchDataForCurrentUrl()
                dialog.dismiss()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun setupRecyclerView() {
        imageAdapter = ImageAdapter()
        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 5)
            adapter = imageAdapter
            setHasFixedSize(true)
        }
    }

    private fun setupClickListener() {
        imageAdapter.onItemClick = { item ->
            val intent = Intent(this, DetailActivity::class.java).apply {
                putExtra(DetailActivity.EXTRA_URL, item.detailUrl)
                putExtra("EXTRA_TITLE", item.title)
            }
            startActivity(intent)
        }
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressBar.isVisible = isLoading
            binding.recyclerView.isVisible = !isLoading
        }

        viewModel.images.observe(this) { items ->
            allItems = items // 全アイテムを更新
            filterImages(null) // 初期表示時はフィルターなし
        }

        viewModel.error.observe(this) { errorMessage ->
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
        }
    }
}
