package com.example.myapplication

import android.os.Bundle
import android.text.Html
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.databinding.ActivityDetailBinding

class DetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding
    private val viewModel: DetailViewModel by viewModels()
    private lateinit var detailAdapter: DetailAdapter

    private lateinit var scrollPositionStore: ScrollPositionStore
    private var currentUrl: String? = null

    private lateinit var layoutManager: LinearLayoutManager

    companion object {
        const val EXTRA_URL = "extra_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        scrollPositionStore = ScrollPositionStore(this)

        val url = intent.getStringExtra(EXTRA_URL)
        val title = intent.getStringExtra("EXTRA_TITLE")

        currentUrl = url
        binding.toolbarTitle.text = title

        if (currentUrl == null) {
            Toast.makeText(this, "Error: URL not provided", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupCustomToolbar()
        setupRecyclerView() // ★★★ 順序変更、observeViewModelより先に呼び出す ★★★
        observeViewModel()
        viewModel.fetchDetails(currentUrl!!)
    }

    private fun setupCustomToolbar() {
        binding.backButton.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.refreshButton.setOnClickListener {
            currentUrl?.let { url ->
                saveCurrentScrollState(url)
                viewModel.fetchDetails(url)
                Toast.makeText(this, "再読み込みしています...", Toast.LENGTH_SHORT).show()
            }
        }
    }


    override fun onPause() {
        super.onPause()
        currentUrl?.let { url ->
            saveCurrentScrollState(url)
        }
    }

    private fun setupRecyclerView() {
        detailAdapter = DetailAdapter()
        layoutManager = LinearLayoutManager(this@DetailActivity)

        // Adapterのコールバックを設定
        detailAdapter.onQuoteClickListener = customLabel@{ quotedText ->
            // ViewModelが保持している現在のコンテンツリストを取得
            // ★★★ ここのラベルを修正 ★★★
            val contentList = viewModel.detailContent.value ?: return@customLabel

            // 引用されたテキストを含むコンテンツをリストから探す
            val targetPosition = contentList.indexOfFirst { content ->
                if (content is DetailContent.Text) {
                    // HTMLタグを除いた平文テキストを取得して比較
                    val plainText = Html.fromHtml(content.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
                    plainText.contains(quotedText)
                } else {
                    false
                }
            }

            // 見つかった場合
            if (targetPosition != -1) {
                // その位置までスムーズにスクロール
                binding.detailRecyclerView.smoothScrollToPosition(targetPosition)
            } else {
                Toast.makeText(this, "引用元が見つかりません", Toast.LENGTH_SHORT).show()
            }
        }

        binding.detailRecyclerView.apply {
            this.layoutManager = this@DetailActivity.layoutManager
            adapter = detailAdapter
            itemAnimator = null
        }
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressBar.isVisible = isLoading
            binding.detailRecyclerView.isVisible = !isLoading
        }

        viewModel.detailContent.observe(this) { contentList ->
            detailAdapter.submitList(contentList)

            if (contentList.isNotEmpty()) {
                currentUrl?.let { url ->
                    val (position, offset) = scrollPositionStore.getScrollState(url)
                    if (position > 0 || offset != 0) {
                        binding.detailRecyclerView.post {
                            layoutManager.scrollToPositionWithOffset(position, offset)
                        }
                    }
                }
            }
        }

        viewModel.error.observe(this) { errorMessage ->
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
        }
    }

    private fun saveCurrentScrollState(url: String) {
        val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()
        if (firstVisibleItemPosition != RecyclerView.NO_POSITION) {
            val firstVisibleItemView = layoutManager.findViewByPosition(firstVisibleItemPosition)
            val offset = firstVisibleItemView?.top ?: 0
            scrollPositionStore.saveScrollState(url, firstVisibleItemPosition, offset)
        }
    }
}