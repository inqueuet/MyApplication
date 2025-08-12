package com.example.myapplication

import android.os.Bundle
import android.text.Html
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.databinding.ActivityDetailBinding
// 必要に応じてRクラスをインポートしてください
// import com.example.myapplication.R

class DetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding
    private val viewModel: DetailViewModel by viewModels()
    private lateinit var detailAdapter: DetailAdapter

    private lateinit var scrollPositionStore: ScrollPositionStore
    private var currentUrl: String? = null

    private lateinit var layoutManager: LinearLayoutManager

    // Search related variables
    private var searchView: SearchView? = null
    private var currentSearchQuery: String? = null
    private val searchResultPositions = mutableListOf<Int>()
    private var currentSearchHitIndex = -1

    companion object {
        const val EXTRA_URL = "extra_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar) // XMLで定義したToolbarをActionBarとして設定

        scrollPositionStore = ScrollPositionStore(this)

        val url = intent.getStringExtra(EXTRA_URL)
        val title = intent.getStringExtra("EXTRA_TITLE")

        currentUrl = url
        // Toolbar内のTextViewにタイトルを設定 (MaterialToolbarのtitleではない)
        binding.toolbarTitle.text = title


        if (currentUrl == null) {
            Toast.makeText(this, "Error: URL not provided", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupCustomToolbarElements() // カスタムツールバー要素のリスナー設定
        setupRecyclerView()
        observeViewModel() // ★★★ observeViewModelはsetupRecyclerViewの後に呼び出す ★★★
        viewModel.fetchDetails(currentUrl!!)
        setupSearchNavigation()
    }

    private fun setupCustomToolbarElements() {
        // XMLレイアウト内の戻るボタンのリスナー
        binding.backButton.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.detail_menu, menu)
        val searchItem = menu.findItem(R.id.action_search)
        searchView = searchItem?.actionView as? SearchView

        searchView?.queryHint = getString(R.string.search_hint)
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let {
                    performSearch(it)
                }
                searchView?.clearFocus() // キーボードを隠す
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (newText.isNullOrEmpty() && currentSearchQuery != null) {
                    // 検索窓がクリアされたら検索状態もクリア
                    clearSearchAndUI()
                }
                return true
            }
        })

        searchItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                binding.searchNavigationControls.visibility = View.VISIBLE
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                clearSearchAndUI()
                return true
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_reload -> {
                currentUrl?.let { urlToRefresh ->
                    saveCurrentScrollStateIfApplicable()
                    clearSearchAndUI()
                    viewModel.fetchDetails(urlToRefresh)
                    Toast.makeText(this, "再読み込みしています...", Toast.LENGTH_SHORT).show()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onPause() {
        super.onPause()
        saveCurrentScrollStateIfApplicable()
    }

    private fun saveCurrentScrollStateIfApplicable() {
        if (searchView?.isIconified != false && currentUrl != null) { // 検索中でない場合のみ
            saveCurrentScrollState(currentUrl!!)
        }
    }


    private fun setupRecyclerView() {
        detailAdapter = DetailAdapter()
        layoutManager = LinearLayoutManager(this@DetailActivity)

        detailAdapter.onQuoteClickListener = customLabel@{ quotedText ->
            val contentList = viewModel.detailContent.value ?: return@customLabel
            val targetPosition = contentList.indexOfFirst { content ->
                if (content is DetailContent.Text) {
                    val plainText = Html.fromHtml(content.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
                    plainText.contains(quotedText, ignoreCase = true)
                } else {
                    false
                }
            }
            if (targetPosition != -1) {
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
            binding.detailRecyclerView.isVisible = !isLoading // ★★★ 簡略化 ★★★
        }

        viewModel.detailContent.observe(this) { contentList ->
            val hadPreviousContent = detailAdapter.itemCount > 0
            detailAdapter.submitList(contentList) {
                // リストが更新された後に実行されるコールバック
                if (contentList.isNotEmpty()) {
                    currentUrl?.let { url ->
                        // ★★★ スクロールロジック修正箇所 ★★★
                        if (currentSearchQuery != null && searchView?.isIconified == false) {
                            // 検索がアクティブな場合は、現在のクエリで再検索（これによりnavigateToCurrentHitが呼ばれる）
                            performSearch(currentSearchQuery!!)
                        } else {
                            // 検索がアクティブでない場合、スクロール位置を復元
                            // 条件: 初回ロードでない場合、または保存された位置/オフセットが(0,0)でない場合
                            if (hadPreviousContent || scrollPositionStore.getScrollState(url).first != 0 || scrollPositionStore.getScrollState(url).second != 0) {
                                val (position, offset) = scrollPositionStore.getScrollState(url)
                                if (position >= 0 && position < contentList.size) {
                                    binding.detailRecyclerView.post {
                                        layoutManager.scrollToPositionWithOffset(position, offset)
                                    }
                                }
                            }
                            // ★★★ 注意: 初回ロードで保存位置がない(0,0)の場合、明示的に先頭にスクロールするロジックはここには含めていません。
                            // 必要であれば `else if (!hadPreviousContent)` のような条件で追加してください。
                            // ただし、多くの場合、何もしなければデフォルトで先頭が表示されます。
                        }
                    }
                } else {
                    clearSearchAndUI() // コンテンツが空なら検索もクリア
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

    private fun performSearch(query: String) {
        currentSearchQuery = query
        detailAdapter.setSearchQuery(query) // Adapterにクエリを渡してハイライトさせる
        searchResultPositions.clear()
        currentSearchHitIndex = -1

        val contentList = viewModel.detailContent.value ?: return
        contentList.forEachIndexed { index, content ->
            var textToSearch: String? = null
            when (content) {
                is DetailContent.Text -> {
                    textToSearch = Html.fromHtml(content.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
                }
                is DetailContent.Image -> {
                    textToSearch = content.prompt // プロンプトも検索対象
                }
                is DetailContent.Video -> {
                    textToSearch = content.prompt // プロンプトも検索対象
                }
            }

            if (textToSearch?.contains(query, ignoreCase = true) == true) {
                searchResultPositions.add(index)
            }
        }

        if (searchResultPositions.isNotEmpty()) {
            currentSearchHitIndex = 0
            navigateToCurrentHit() // 最初のヒットへ移動
            binding.searchNavigationControls.visibility = View.VISIBLE
        } else {
            Toast.makeText(this, getString(R.string.no_results_found), Toast.LENGTH_SHORT).show()
            binding.searchNavigationControls.visibility = View.GONE
        }
        updateSearchResultsCount()
    }

    private fun clearSearchAndUI() {
        currentSearchQuery = null
        detailAdapter.setSearchQuery(null) // Adapterのハイライトもクリア
        searchResultPositions.clear()
        currentSearchHitIndex = -1
        binding.searchNavigationControls.visibility = View.GONE
        updateSearchResultsCount()

        if (searchView?.isIconified == false) {
            searchView?.setQuery("", false)
            searchView?.isIconified = true
        }

        if (viewModel.isLoading.value == false) { // ローディング中でなければ表示
            binding.detailRecyclerView.isVisible = true
        }
    }

    private fun navigateToCurrentHit() {
        if (searchResultPositions.isNotEmpty() && currentSearchHitIndex in searchResultPositions.indices) {
            val position = searchResultPositions[currentSearchHitIndex]
            if (position >= 0 && position < detailAdapter.itemCount) {
                // ★★★ スクロール方法の変更 ★★★
                binding.detailRecyclerView.post {
                    layoutManager.scrollToPositionWithOffset(position, 0)
                }
            }
        }
        updateSearchResultsCount()
    }

    private fun setupSearchNavigation() {
        binding.searchUpButton.setOnClickListener {
            if (searchResultPositions.isNotEmpty()) {
                currentSearchHitIndex--
                if (currentSearchHitIndex < 0) {
                    currentSearchHitIndex = searchResultPositions.size - 1
                }
                navigateToCurrentHit()
            }
        }

        binding.searchDownButton.setOnClickListener {
            if (searchResultPositions.isNotEmpty()) {
                currentSearchHitIndex++
                if (currentSearchHitIndex >= searchResultPositions.size) {
                    currentSearchHitIndex = 0
                }
                navigateToCurrentHit()
            }
        }
    }

    private fun updateSearchResultsCount() {
        if (searchResultPositions.isNotEmpty() && currentSearchHitIndex != -1) {
            binding.searchResultsCountText.text =
                getString(R.string.search_results_format, currentSearchHitIndex + 1, searchResultPositions.size)
        } else if (currentSearchQuery != null && searchResultPositions.isEmpty()) {
            binding.searchResultsCountText.text = getString(R.string.no_results_found)
        } else {
            binding.searchResultsCountText.text = ""
        }
    }
}