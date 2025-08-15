package com.example.hutaburakari

import android.content.Intent // Intent をインポート
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.Menu
import android.view.MenuItem
// import android.webkit.WebResourceError // No longer needed
// import android.webkit.WebResourceRequest // No longer needed
// import android.webkit.WebView // No longer needed
// import android.webkit.WebViewClient // No longer needed
// import android.widget.Button // No longer needed
// import android.widget.EditText // No longer needed
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
// import androidx.activity.result.ActivityResultLauncher // No longer needed
// import androidx.activity.result.contract.ActivityResultContracts // No longer needed
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.hutaburakari.databinding.ActivityDetailBinding
import okhttp3.* // Keep for okHttpCookieJar and okHttpClient if they are used by other features
// import okhttp3.HttpUrl.Companion.toHttpUrlOrNull // No longer needed directly here
// import okhttp3.MediaType.Companion.toMediaTypeOrNull // Was for CommentSender
// import okhttp3.RequestBody.Companion.toRequestBody // Was for CommentSender
// import java.io.IOException // No longer directly used here
// import java.nio.charset.Charset // No longer directly used here
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import android.text.Html // Html.fromHtml のために必要

class DetailActivity : AppCompatActivity(), SearchManagerCallback {

    private lateinit var binding: ActivityDetailBinding
    private val viewModel: DetailViewModel by viewModels()
    private lateinit var detailAdapter: DetailAdapter
    private lateinit var scrollPositionStore: ScrollPositionStore
    private var currentUrl: String? = null
    private lateinit var layoutManager: LinearLayoutManager

    private val scrollHistory = ArrayDeque<Pair<Int, Int>>(2)

    private lateinit var detailSearchManager: DetailSearchManager

    // File Picker and related UI (Removed as part of comment functionality removal)
    // private lateinit var filePickerLauncher: ActivityResultLauncher<Array<String>> // Removed
    // private var selectedFileUri: Uri? = null // Removed
    // private var textViewSelectedFileName: TextView? = null // Removed
    // private val maxFileSizeBytes = 8 * 1024 * 1024 // 8MB - Keep if other file uploads exist, remove if only for comments
    // For now, assume it might be used elsewhere, if not, it can be removed later.
    private val maxFileSizeBytes = 8 * 1024 * 1024 // 8MB, potentially for other features?

    // Background WebView and Submission Process (Removed)
    // private var backgroundWebView: WebView? = null // Removed

    // private var commentFormDataMap: MutableMap<String, Any?> = mutableMapOf() // Removed

    private val okHttpCookieJar: CookieJar = object : CookieJar {
        private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            val currentCookies = cookieStore.getOrPut(host) { mutableListOf() }
            cookies.forEach { newCookie ->
                currentCookies.removeAll { it.name == newCookie.name }
            }
            currentCookies.addAll(cookies)
            Log.d("DetailActivity_OkHttpCookieJar", "Saved cookies for $host: ${cookieStore[host]}")
            val webViewCookieManager = android.webkit.CookieManager.getInstance()
            cookies.forEach { cookie ->
                val cookieString = cookie.toString()
                webViewCookieManager.setCookie(url.toString(), cookieString)
            }
            webViewCookieManager.flush()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val host = url.host
            val storedCookies = cookieStore[host] ?: emptyList()
            val validCookies = storedCookies.filter { it.expiresAt > System.currentTimeMillis() }
            Log.d("DetailActivity_OkHttpCookieJar", "Loading cookies for $host (found ${validCookies.size}/${storedCookies.size}): $validCookies")
            return validCookies
        }
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(okHttpCookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        const val EXTRA_URL = "extra_url"
        // Constants related to comments removed
        // const val KEY_NAME = "name"
        // const val KEY_EMAIL = "email"
        // const val KEY_SUBJECT = "subject"
        // const val KEY_COMMENT = "comment"
        // const val KEY_SELECTED_FILE_URI = "selectedFileUri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        scrollPositionStore = ScrollPositionStore(this)
        detailSearchManager = DetailSearchManager(binding, this)

        val url = intent.getStringExtra(EXTRA_URL)
        val title = intent.getStringExtra("EXTRA_TITLE")

        currentUrl = url
        binding.toolbarTitle.text = title

        if (currentUrl == null) {
            Toast.makeText(this, "Error: URL not provided", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupCustomToolbarElements()
        setupRecyclerView()
        observeViewModel()
        viewModel.fetchDetails(currentUrl!!)
        detailSearchManager.setupSearchNavigation()

        // filePickerLauncher initialization removed

        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (detailSearchManager.handleOnBackPressed()) {
                    return
                }
                if (scrollHistory.isNotEmpty()) {
                    val (position, offset) = scrollHistory.removeLast()
                    if (position >= 0 && position < detailAdapter.itemCount) {
                        binding.detailRecyclerView.post {
                            layoutManager.scrollToPositionWithOffset(position, offset)
                        }
                    } else {
                        Toast.makeText(this@DetailActivity, "戻る先の位置が無効です。", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }

    private fun setupCustomToolbarElements() {
        binding.backButton.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    // initializeBackgroundWebView method removed

    // resetCommentSubmissionState method removed

    // handleCommentSubmissionSuccess method removed

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.detail_menu, menu)
        detailSearchManager.setupSearch(menu)
        // menu.findItem(R.id.action_write)?.isVisible = false // This line can be reactivated if you want to explicitly hide the option
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_reply -> {
                currentUrl?.let { url ->
                    // スレッドIDをURLから抽出 (例: .../res/12345.htm -> 12345)
                    val threadId = url.substringAfterLast("/").substringBefore(".htm")
                    val boardUrl = url.substringBeforeLast("/").substringBeforeLast("/") + "/futaba.php" // 例: https://may.2chan.net/b/futaba.php
                    val intent = Intent(this, ReplyActivity::class.java).apply {
                        putExtra(ReplyActivity.EXTRA_THREAD_ID, threadId)
                        putExtra(ReplyActivity.EXTRA_THREAD_TITLE, binding.toolbarTitle.text.toString())
                        putExtra(ReplyActivity.EXTRA_BOARD_URL, boardUrl) // ボードのベースURLも渡す
                    }
                    startActivity(intent)
                }
                true
            }
            R.id.action_reload -> {
                currentUrl?.let { urlToRefresh ->
                    saveCurrentScrollStateIfApplicable()
                    detailSearchManager.clearSearch()
                    scrollHistory.clear()
                    viewModel.fetchDetails(urlToRefresh, forceRefresh = true)
                    showToastOnUiThread("再読み込みしています...", Toast.LENGTH_SHORT)
                }
                true
            }
            R.id.action_scroll_back -> {
                if (scrollHistory.isNotEmpty()) {
                    val (position, offset) = scrollHistory.removeLast()
                    if (position >= 0 && position < detailAdapter.itemCount) {
                        binding.detailRecyclerView.post {
                            layoutManager.scrollToPositionWithOffset(position, offset)
                        }
                    } else {
                        showToastOnUiThread("戻る先の位置が無効です。", Toast.LENGTH_SHORT)
                    }
                } else {
                    showToastOnUiThread("戻る履歴がありません。", Toast.LENGTH_SHORT)
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

    override fun onDestroy() {
        // backgroundWebView related cleanup removed
        super.onDestroy()
    }

    private fun saveCurrentScrollStateIfApplicable() {
        if (!detailSearchManager.isSearchActive() && currentUrl != null) {
            saveCurrentScrollState(currentUrl!!)
        }
    }

    private fun setupRecyclerView() {
        detailAdapter = DetailAdapter()
        layoutManager = LinearLayoutManager(this@DetailActivity)
        detailAdapter.onQuoteClickListener = customLabel@ {
            currentQuotedText ->
            val contentList = viewModel.detailContent.value ?: return@customLabel
            val currentFirstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()
            if (currentFirstVisibleItemPosition != RecyclerView.NO_POSITION) {
                val firstVisibleItemView = layoutManager.findViewByPosition(currentFirstVisibleItemPosition)
                val offset = firstVisibleItemView?.top ?: 0
                if (scrollHistory.size == 2) {
                    scrollHistory.removeFirst()
                }
                scrollHistory.addLast(Pair(currentFirstVisibleItemPosition, offset))
            }
            val targetPosition = contentList.indexOfFirst { content ->
                when (content) {
                    is DetailContent.Text -> Html.fromHtml(content.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString().contains(currentQuotedText, ignoreCase = true)
                    is DetailContent.Image -> {
                        (content.fileName?.equals(currentQuotedText, ignoreCase = true) == true ||
                                content.imageUrl.substringAfterLast('/').equals(currentQuotedText, ignoreCase = true) ||
                                content.prompt?.contains(currentQuotedText, ignoreCase = true) == true)
                    }
                    is DetailContent.Video -> {
                        (content.fileName?.equals(currentQuotedText, ignoreCase = true) == true ||
                                content.videoUrl.substringAfterLast('/').equals(currentQuotedText, ignoreCase = true) ||
                                content.prompt?.contains(currentQuotedText, ignoreCase = true) == true)
                    }
                    is DetailContent.ThreadEndTime -> false
                }
            }
            if (targetPosition != -1) {
                binding.detailRecyclerView.smoothScrollToPosition(targetPosition)
            } else {
                showToastOnUiThread("引用元が見つかりません: $currentQuotedText", Toast.LENGTH_SHORT)
            }
        }
        detailAdapter.onSodaNeClickListener = {
            resNum -> viewModel.postSodaNe(resNum)
        }
        binding.detailRecyclerView.apply {
            layoutManager = this@DetailActivity.layoutManager
            adapter = detailAdapter
            itemAnimator = null
        }
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressBar.isVisible = isLoading
            binding.detailRecyclerView.isVisible = !isLoading && !detailSearchManager.isSearchActive()
        }
        viewModel.detailContent.observe(this) { contentList ->
            val hadPreviousContent = detailAdapter.itemCount > 0
            detailAdapter.submitList(contentList) {
                if (contentList.isNotEmpty()) {
                    currentUrl?.let { url ->
                        if (detailSearchManager.getCurrentSearchQuery() != null && detailSearchManager.isSearchViewExpanded()) {
                            detailSearchManager.getCurrentSearchQuery()?.let { query ->
                                detailSearchManager.performSearch(query)
                            }
                        } else {
                            if (hadPreviousContent || scrollPositionStore.getScrollState(url).first != 0 || scrollPositionStore.getScrollState(url).second != 0) {
                                val (position, offset) = scrollPositionStore.getScrollState(url)
                                if (position >= 0 && position < contentList.size) {
                                    binding.detailRecyclerView.post {
                                        layoutManager.scrollToPositionWithOffset(position, offset)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    detailSearchManager.clearSearch()
                }
            }
        }
        viewModel.error.observe(this) { errorMessage ->
            if (!errorMessage.isNullOrEmpty()) {
                showToastOnUiThread(errorMessage, Toast.LENGTH_LONG)
            }
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

    private fun getFileNameFromUri(uri: Uri): String? {
        var fileNameResult: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex != -1) {
                        fileNameResult = cursor.getString(displayNameIndex)
                    } else {
                        Log.w("DetailActivity", "Column OpenableColumns.DISPLAY_NAME not found in getFileNameFromUri.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DetailActivity", "Error getting file name from ContentResolver for URI $uri", e)
        }
        if (fileNameResult == null) {
            fileNameResult = uri.path?.substringAfterLast('/')
            if (fileNameResult.isNullOrEmpty()) Log.w("DetailActivity", "Could not derive filename from URI path: $uri")
        }
        return fileNameResult
    }

    private fun getFileSize(uri: Uri): Long? {
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> return pfd.statSize }
        } catch (e: Exception) {
            Log.e("DetailActivity", "Error getting file size for URI: $uri", e)
        }
        return null
    }

    private fun showToastOnUiThread(message: String, duration: Int) {
        runOnUiThread {
            Toast.makeText(this, message, if (duration == 1) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    // Implementation of SearchManagerCallback
    override fun getDetailContent(): List<DetailContent>? {
        return viewModel.detailContent.value
    }

    override fun getDetailAdapter(): DetailAdapter {
        return detailAdapter
    }

    override fun getLayoutManager(): LinearLayoutManager {
        return layoutManager
    }

    override fun showToast(message: String, duration: Int) {
        Toast.makeText(this, message, duration).show()
    }

    override fun getStringResource(resId: Int): String {
        return getString(resId)
    }

    override fun getStringResource(resId: Int, vararg formatArgs: Any): String {
        return getString(resId, *formatArgs)
    }

    override fun onSearchCleared() {
        if (isBindingInitialized() && viewModel.isLoading.value == false ) {
             binding.detailRecyclerView.isVisible = true
        }
    }

    override fun isBindingInitialized(): Boolean {
        return ::binding.isInitialized
    }
}
