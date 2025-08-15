
package com.example.myapplication

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.databinding.ActivityDetailBinding
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
// MediaType.Companion.toMediaTypeOrNull and RequestBody.Companion.toRequestBody are used by CommentSender
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

    // File Picker and related UI
    private lateinit var filePickerLauncher: ActivityResultLauncher<Array<String>>
    private var selectedFileUri: Uri? = null
    private var textViewSelectedFileName: TextView? = null
    private val maxFileSizeBytes = 8 * 1024 * 1024 // 8MB

    // Background WebView and Submission Process
    private var backgroundWebView: WebView? = null
    private var targetPageUrlForFields: String? = null
    private var currentBoardId: String? = null
    private var isSubmissionProcessActive = false
    private var commentFormDataMap: MutableMap<String, Any?> = mutableMapOf()

    // Hidden Form Fields Retrieval
    private var hiddenFormValues = mutableMapOf<String, String>()
    private var currentHiddenFieldStep = 0
    private val hiddenFieldSelectors = listOf(
        "baseform", "pthb", "pthc", "pthd", "ptua", "scsz", "hash", "chrenc"
    )

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

    private lateinit var commentSender: CommentSender

    companion object {
        const val EXTRA_URL = "extra_url"
        // KEY_NAME, KEY_EMAIL, KEY_SUBJECT, KEY_COMMENT, KEY_SELECTED_FILE_URI
        // are now in CommentSender.companion object
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        scrollPositionStore = ScrollPositionStore(this)
        detailSearchManager = DetailSearchManager(binding, this)

        commentSender = CommentSender(
            okHttpClient = this.okHttpClient,
            okHttpCookieJar = this.okHttpCookieJar,
            contentResolver = this.contentResolver, // Changed from applicationContext.contentResolver
            getFileNameFn = this::getFileNameFromUri, 
            runOnUiThreadFn = { action -> this.runOnUiThread(action) }, // Wrapped in a lambda
            resetSubmissionStateFn = this::resetCommentSubmissionState, 
            showToastFn = this::showToastOnUiThread, 
            onSubmissionSuccessFn = this::handleCommentSubmissionSuccess
        )

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

        filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                val fileSize = getFileSize(uri)
                val fileNameToDisplay = getFileNameFromUri(uri) 
                if (fileSize != null && fileSize > maxFileSizeBytes) {
                    val maxSizeMB = maxFileSizeBytes / (1024.0 * 1024.0)
                    Toast.makeText(this, getString(R.string.error_file_too_large_format, maxSizeMB), Toast.LENGTH_LONG).show()
                    this.selectedFileUri = null
                    commentFormDataMap.remove(CommentSender.KEY_SELECTED_FILE_URI)
                    textViewSelectedFileName?.text = getString(R.string.text_no_file_selected)
                } else {
                    this.selectedFileUri = uri
                    commentFormDataMap[CommentSender.KEY_SELECTED_FILE_URI] = uri
                    textViewSelectedFileName?.text = fileNameToDisplay ?: uri.toString()
                }
            } else {
                this.selectedFileUri = null
                commentFormDataMap.remove(CommentSender.KEY_SELECTED_FILE_URI)
                textViewSelectedFileName?.text = getString(R.string.text_no_file_selected)
            }
        }

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

    private fun initializeBackgroundWebView() {
        if (backgroundWebView == null) {
            Log.d("DetailActivity", "Initializing backgroundWebView.")
            backgroundWebView = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d("DetailActivity", "WebView onPageFinished: $url, isSubmissionProcessActive: $isSubmissionProcessActive, targetPageUrlForFields: $targetPageUrlForFields")
                        if (isSubmissionProcessActive && url == targetPageUrlForFields) {
                            val webViewCookieManager = android.webkit.CookieManager.getInstance()
                            val cookiesHeaderFromWebView = webViewCookieManager.getCookie(url)
                            Log.d("DetailActivity", "Cookies string from backgroundWebView after page finished ($url): '$cookiesHeaderFromWebView'")

                            if (!cookiesHeaderFromWebView.isNullOrEmpty()) {
                                val httpUrl = url!!.toHttpUrlOrNull()
                                if (httpUrl != null) {
                                    cookiesHeaderFromWebView.split(";").forEach { cookieString ->
                                        Cookie.parse(httpUrl, cookieString.trim())?.let {
                                            okHttpCookieJar.saveFromResponse(httpUrl, listOf(it))
                                        }
                                    }
                                }
                            }
                            currentHiddenFieldStep = 0
                            hiddenFormValues.clear()
                            executeHiddenFieldJsStep(view)
                        } else if (isSubmissionProcessActive) { // Condition `url != targetPageUrlForFields` is always true here
                            Log.w("DetailActivity", "WebView navigated to unexpected URL ('$url') during submission, expecting '$targetPageUrlForFields'")
                            resetCommentSubmissionState("ページ準備中に予期せぬURLに遷移: $url")
                        }
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        Log.e("DetailActivity", "WebView onReceivedError: ${error?.description} for ${request?.url}, isForMainFrame: ${request?.isForMainFrame}")
                        if (request?.isForMainFrame == true && request.url.toString() == targetPageUrlForFields) {
                            resetCommentSubmissionState("ページの読み込みに失敗: ${error?.description ?: "\"不明なエラー\""}")
                        }
                    }
                }
            }
        } else {
            Log.d("DetailActivity", "backgroundWebView already initialized.")
        }
    }

    private fun executeHiddenFieldJsStep(webView: WebView?) {
        if (webView == null) {
            Log.w("DetailActivity", "executeHiddenFieldJsStep: WebView is null.")
            resetCommentSubmissionState("WebViewが利用できません。")
            return
        }
        if (currentHiddenFieldStep < hiddenFieldSelectors.size) {
            val fieldName = hiddenFieldSelectors[currentHiddenFieldStep]
            val jsToExecute = "(function() { var el = document.querySelector('input[name=\'${fieldName}\']'); return el ? el.value : ''; })();"
            Log.d("DetailActivity", "Executing JS for hidden field: $fieldName, JS: $jsToExecute")
            webView.evaluateJavascript(jsToExecute) { result ->
                val valueResult = result?.trim()?.removeSurrounding("'''") ?: ""
                Log.d("DetailActivity", "Hidden field: '$fieldName' = '$valueResult'")
                hiddenFormValues[fieldName] = valueResult
                currentHiddenFieldStep++
                executeHiddenFieldJsStep(webView)
            }
        } else {
            Log.d("DetailActivity", "All hidden fields collected: $hiddenFormValues. Proceeding to send with CommentSender.")
            val submissionUrl = "https://may.2chan.net/b/futaba.php?guid=on" 

            commentSender.sendComment(
                commentFormDataMap = commentFormDataMap,
                hiddenFormValues = hiddenFormValues.toMap(), 
                currentBoardId = currentBoardId,
                maxFileSizeBytes = maxFileSizeBytes.toLong(),
                targetPageUrlForFields = targetPageUrlForFields,
                submissionUrl = submissionUrl
            )
        }
    }

    private fun resetCommentSubmissionState(errorMessage: String? = null) { 
        Log.d("DetailActivity", "Resetting submission state. Message: $errorMessage")
        isSubmissionProcessActive = false
        if (errorMessage != null) {
            showToastOnUiThread(errorMessage, Toast.LENGTH_LONG)
        }
    }
    
    private fun handleCommentSubmissionSuccess(responseBody: String) {
        runOnUiThread {
            Log.i("DetailActivity", "Comment submission successful via CommentSender!")
            showToastOnUiThread("コメントを送信しました。", Toast.LENGTH_LONG)
            commentFormDataMap.clear()
            selectedFileUri = null
            textViewSelectedFileName?.text = getString(R.string.text_no_file_selected) 
            resetCommentSubmissionState() 
            currentUrl?.let { urlToRefresh ->
                showToastOnUiThread("スレッドを再読み込みします...", Toast.LENGTH_SHORT)
                viewModel.fetchDetails(urlToRefresh, forceRefresh = true)
            }
            Log.d("DetailActivity", "Response on success (first 200 chars): ${responseBody.take(200)}")
        }
    }

    private fun showAddCommentDialog() {
        if (currentUrl == null || !currentUrl!!.startsWith("https://may.2chan.net/b/res/")) {
            Toast.makeText(this, "このURLでは書き込みモードはサポートされていません。", Toast.LENGTH_LONG).show()
            return
        }
        val idWithExtension = currentUrl!!.substringAfterLast("/")
        val id = idWithExtension.substringBefore(".htm")
        if (id.isEmpty()) {
            Toast.makeText(this, "URLからIDを取得できませんでした。", Toast.LENGTH_LONG).show()
            return
        }
        currentBoardId = id

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_comment, null)
        val editTextName = dialogView.findViewById<EditText>(R.id.edit_text_name)
        val editTextEmail = dialogView.findViewById<EditText>(R.id.edit_text_email)
        val editTextSubject = dialogView.findViewById<EditText>(R.id.edit_text_subject)
        val editTextComment = dialogView.findViewById<EditText>(R.id.edit_text_comment)
        val buttonSelectFile = dialogView.findViewById<Button>(R.id.button_select_file)
        textViewSelectedFileName = dialogView.findViewById(R.id.text_selected_file_name) // Type argument inferred

        editTextName.setText(commentFormDataMap[CommentSender.KEY_NAME] as? String ?: "")
        editTextEmail.setText(commentFormDataMap[CommentSender.KEY_EMAIL] as? String ?: "")
        editTextSubject.setText(commentFormDataMap[CommentSender.KEY_SUBJECT] as? String ?: "")
        editTextComment.setText(commentFormDataMap[CommentSender.KEY_COMMENT] as? String ?: "")

        val previouslySelectedUri = commentFormDataMap[CommentSender.KEY_SELECTED_FILE_URI] as? Uri
        if (previouslySelectedUri != null) {
            this.selectedFileUri = previouslySelectedUri
            textViewSelectedFileName?.text = getFileNameFromUri(previouslySelectedUri) ?: previouslySelectedUri.toString()
        } else {
            this.selectedFileUri = null
            textViewSelectedFileName?.text = getString(R.string.text_no_file_selected)
        }

        buttonSelectFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf("image/gif", "image/jpeg", "image/png", "video/webm", "video/mp4", "*/*"))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.add_comment_dialog_title)
            .setView(dialogView)
            .setPositiveButton(R.string.button_send) { dialog, _ ->
                commentFormDataMap[CommentSender.KEY_NAME] = editTextName.text.toString()
                commentFormDataMap[CommentSender.KEY_EMAIL] = editTextEmail.text.toString()
                commentFormDataMap[CommentSender.KEY_SUBJECT] = editTextSubject.text.toString()
                commentFormDataMap[CommentSender.KEY_COMMENT] = editTextComment.text.toString()
                targetPageUrlForFields = currentUrl
                Log.d("DetailActivity", "AddCommentDialog: Send clicked. Target page for fields: $targetPageUrlForFields")
                performCommentSubmission()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.button_cancel) { dialog, _ ->
                textViewSelectedFileName = null 
                dialog.cancel()
            }
            .setOnDismissListener {
                textViewSelectedFileName = null
            }
            .show()
    }

    private fun performCommentSubmission() {
        if (isSubmissionProcessActive) {
            Log.w("DetailActivity", "performCommentSubmission: Already active.")
            Toast.makeText(this, "既に処理を実行中です。", Toast.LENGTH_SHORT).show()
            return
        }
        if (targetPageUrlForFields == null || currentBoardId == null) {
            Log.e("DetailActivity", "performCommentSubmission: Submission info missing. targetPageUrlForFields=$targetPageUrlForFields, currentBoardId=$currentBoardId")
            resetCommentSubmissionState("送信情報が不足しています(URL/ID)。")
            return
        }
        Log.d("DetailActivity", "Performing comment submission to board ID: $currentBoardId, from URL: $currentUrl, target page for fields: $targetPageUrlForFields")

        initializeBackgroundWebView()
        isSubmissionProcessActive = true
        currentHiddenFieldStep = 0
        hiddenFormValues.clear()

        backgroundWebView?.let {
            Log.d("DetailActivity", "Loading URL in backgroundWebView for hidden fields: $targetPageUrlForFields")
            it.loadUrl(targetPageUrlForFields!!)
            showToastOnUiThread("投稿準備中です...", Toast.LENGTH_SHORT) 
        } ?: run {
            Log.e("DetailActivity", "performCommentSubmission: backgroundWebView is null before loading URL.")
            resetCommentSubmissionState("WebViewの初期化に失敗しました。")
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.detail_menu, menu)
        detailSearchManager.setupSearch(menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
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
            R.id.action_write -> { showAddCommentDialog(); true }
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
        Log.d("DetailActivity", "onDestroy called, destroying backgroundWebView.")
        backgroundWebView?.stopLoading()
        backgroundWebView?.destroy()
        backgroundWebView = null
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
        detailAdapter.onQuoteClickListener = customLabel@{
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
