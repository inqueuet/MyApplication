package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.myapplication.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var imageAdapter: ImageAdapter

    private val defaultUrl = "https://may.2chan.net/b/futaba.php?mode=cat&sort=3"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        // UIの準備
        setupRecyclerView()
        observeViewModel()
        setupClickListener()

        // ★★★ ここから処理を修正 ★★★
        // 非同期処理(コルーチン)を開始
        lifecycleScope.launch {
            // 1. 設定をサーバーに送信してCookieを更新
            applyCatalogSettings()
            // 2. 準備が整ったので、ViewModelにデータ取得を依頼
            viewModel.fetchImagesFromUrl(defaultUrl)
        }
        // ★★★ ここまで修正 ★★★
    }

    // ★★★ 新しく追加した非同期の関数 ★★★
    /**
     * カタログの設定値をサーバーに送信する
     */
    private suspend fun applyCatalogSettings() {
        // 送信する設定データ
        val settings = mapOf(
            "mode" to "catset", // 必須パラメータ
            "cx" to "20",   // カタログの横サイズ
            "cy" to "10",   // カタログの縦サイズ
            "cl" to "10"    // テキストの表示文字数
        )
        try {
            // NetworkClientの新しい関数を呼び出す
            NetworkClient.applySettings(this, settings)
            // UIスレッドでToastを表示
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "カタログ設定を適用しました", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "設定の適用に失敗: ${e.message}", Toast.LENGTH_LONG).show()
            }
            e.printStackTrace()
        }
    }
    // ★★★ ここまで追加 ★★★


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_reload -> {
                viewModel.fetchImagesFromUrl(defaultUrl)
                Toast.makeText(this, getString(R.string.reloading), Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
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
            // ★★★ この中のif文をなくし、シンプルにします ★★★
            binding.progressBar.isVisible = isLoading
            binding.recyclerView.isVisible = !isLoading
        }

        viewModel.images.observe(this) { items ->
            imageAdapter.submitList(items)
        }

        viewModel.error.observe(this) { errorMessage ->
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
        }
    }
}