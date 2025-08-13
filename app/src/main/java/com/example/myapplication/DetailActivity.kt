package com.example.myapplication

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns // getFileName で使用
import android.text.Html // performSearch, setupRecyclerView で使用
import android.util.Log
import android.view.LayoutInflater // showAddCommentDialog で使用
import android.view.Menu // onCreateOptionsMenu で使用
import android.view.MenuItem // onOptionsItemSelected, onCreateOptionsMenu で使用
import android.view.View // onCreateOptionsMenu, performSearch, clearSearchAndUI で使用
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button // showAddCommentDialog で使用
import android.widget.EditText // showAddCommentDialog で使用
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView // onCreateOptionsMenu で使用
import androidx.core.view.isVisible // observeViewModel, clearSearchAndUI で使用
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView // setupRecyclerView, saveCurrentScrollState で使用
import com.example.myapplication.databinding.ActivityDetailBinding
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull // sendCommentWithOkHttp で使用
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody // sendCommentWithOkHttp で使用
import java.io.IOException // sendCommentWithOkHttp で使用
// import java.text.DecimalFormat // 未使用のためコメントアウト
import java.util.concurrent.TimeUnit
import okhttp3.JavaNetCookieJar
import android.app.AlertDialog // showAddCommentDialogで使用


class DetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding
    private val viewModel: DetailViewModel by viewModels()
    private lateinit var detailAdapter: DetailAdapter // setupRecyclerViewで使用

    private lateinit var scrollPositionStore: ScrollPositionStore
    private var currentUrl: String? = null

    private lateinit var layoutManager: LinearLayoutManager // setupRecyclerViewで使用

    // Search related variables
    private var searchView: SearchView? = null // onCreateOptionsMenu, onOptionsItemSelected, saveCurrentScrollStateIfApplicable, clearSearchAndUI で使用
    private var currentSearchQuery: String? = null // onCreateOptionsMenu, observeViewModel, performSearch, clearSearchAndUI, updateSearchResultsCount で使用
    private val searchResultPositions = mutableListOf<Int>() // performSearch, clearSearchAndUI, navigateToCurrentHit, setupSearchNavigation, updateSearchResultsCount で使用
    private var currentSearchHitIndex = -1 // performSearch, clearSearchAndUI, navigateToCurrentHit, setupSearchNavigation, updateSearchResultsCount で使用

    // File Picker and related UI
    private lateinit var filePickerLauncher: ActivityResultLauncher<Array<String>>
    private var selectedFileUri: Uri? = null
    private var textViewSelectedFileName: TextView? = null
    private val maxFileSizeBytes = 8 * 1024 * 1024 // 8MB (命名規則修正)

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

    private val cookieManager = java.net.CookieManager().apply {
        setCookiePolicy(java.net.CookiePolicy.ACCEPT_ALL)
    }
    private val cookieJar = JavaNetCookieJar(cookieManager)

    private val okHttpClient = OkHttpClient.Builder() // sendCommentWithOkHttp で使用
        .cookieJar(cookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        const val EXTRA_URL = "extra_url"
        const val KEY_NAME = "name"
        const val KEY_EMAIL = "email"
        const val KEY_SUBJECT = "subject"
        const val KEY_COMMENT = "comment"
        const val KEY_SELECTED_FILE_URI = "selected_file_uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

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

        setupCustomToolbarElements()
        setupRecyclerView()
        observeViewModel()
        viewModel.fetchDetails(currentUrl!!) // forceRefresh is false by default
        setupSearchNavigation()

        filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                val fileSize = getFileSize(uri)
                val fileNameToDisplay = getFileName(uri)
                // fileSizeのnullチェックと安全な比較
                if (fileSize != null && fileSize > maxFileSizeBytes) {
                    val maxSizeMB = maxFileSizeBytes / (1024.0 * 1024.0)
                    Toast.makeText(this, getString(R.string.error_file_too_large_format, maxSizeMB), Toast.LENGTH_LONG).show()
                    this.selectedFileUri = null
                    commentFormDataMap.remove(KEY_SELECTED_FILE_URI)
                    textViewSelectedFileName?.text = getString(R.string.text_no_file_selected)
                } else {
                    this.selectedFileUri = uri
                    commentFormDataMap[KEY_SELECTED_FILE_URI] = uri
                    textViewSelectedFileName?.text = fileNameToDisplay ?: uri.toString()
                }
            } else {
                this.selectedFileUri = null
                commentFormDataMap.remove(KEY_SELECTED_FILE_URI)
                textViewSelectedFileName?.text = getString(R.string.text_no_file_selected)
            }
        }
    }

    // Unresolved reference 'setupCustomToolbarElements' を解決するためにこの関数を追加します
    private fun setupCustomToolbarElements() {
        binding.backButton.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun initializeBackgroundWebView() {
        if (backgroundWebView == null) {
            backgroundWebView = WebView(this).apply {
                settings.javaScriptEnabled = true // XSS脆弱性の可能性に関する警告は残ります
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (isSubmissionProcessActive && url == targetPageUrlForFields) {
                            val webViewCookieManager = android.webkit.CookieManager.getInstance()
                            val cookiesOnWebView = webViewCookieManager.getCookie(url) // cookiesOnWebView はここで宣言
                            Log.d("DetailActivity", "Cookies on backgroundWebView after page finished ($url): $cookiesOnWebView")

                            currentHiddenFieldStep = 0
                            hiddenFormValues.clear()
                            executeHiddenFieldJsStep(view)
                            // 'url != targetPageUrlForFields' の条件が常にtrueという警告はロジック次第なので一旦そのまま (targetPageUrlForFieldsがnullでない前提ならOK)
                        } else if (isSubmissionProcessActive && url != targetPageUrlForFields) {
                            resetSubmissionState("ページ準備中に予期せぬURLに遷移: $url")
                        }
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true && request.url.toString() == targetPageUrlForFields) {
                            resetSubmissionState("ページの読み込みに失敗: ${error?.description ?: "不明なエラー"}")
                        }
                    }
                }
            }
        }
    }

    private fun executeHiddenFieldJsStep(webView: WebView?) {
        if (webView == null) {
            resetSubmissionState("WebViewが利用できません。")
            return
        }
        if (currentHiddenFieldStep < hiddenFieldSelectors.size) {
            val fieldName = hiddenFieldSelectors[currentHiddenFieldStep]
            // JavaScript文字列のエスケープを修正
            val jsToExecute = "(function() { var el = document.querySelector('input[name=\'${fieldName}\']'); return el ? el.value : ''; })();"
            webView.evaluateJavascript(jsToExecute) { result ->
                val value = result?.removeSurrounding("\"") ?: "" // valueはここで使用される
                hiddenFormValues[fieldName] = value
                currentHiddenFieldStep++
                executeHiddenFieldJsStep(webView)
            }
        } else {
            sendCommentWithOkHttp()
        }
    }

    private fun resetSubmissionState(toastMessage: String? = null) {
        isSubmissionProcessActive = false
        currentHiddenFieldStep = 0
        if (toastMessage != null) {
            runOnUiThread {
                Toast.makeText(this@DetailActivity, toastMessage, Toast.LENGTH_LONG).show()
            }
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
        textViewSelectedFileName = dialogView.findViewById<TextView>(R.id.text_selected_file_name)

        editTextName.setText(commentFormDataMap[KEY_NAME] as? String ?: "")
        editTextEmail.setText(commentFormDataMap[KEY_EMAIL] as? String ?: "")
        editTextSubject.setText(commentFormDataMap[KEY_SUBJECT] as? String ?: "")
        editTextComment.setText(commentFormDataMap[KEY_COMMENT] as? String ?: "")

        val previouslySelectedUri = commentFormDataMap[KEY_SELECTED_FILE_URI] as? Uri
        if (previouslySelectedUri != null) {
            this.selectedFileUri = previouslySelectedUri
            textViewSelectedFileName?.text = getFileName(previouslySelectedUri) ?: previouslySelectedUri.toString()
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
                commentFormDataMap[KEY_NAME] = editTextName.text.toString()
                commentFormDataMap[KEY_EMAIL] = editTextEmail.text.toString()
                commentFormDataMap[KEY_SUBJECT] = editTextSubject.text.toString()
                commentFormDataMap[KEY_COMMENT] = editTextComment.text.toString()
                targetPageUrlForFields = currentUrl
                performCommentSubmission()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.button_cancel) { dialog, _ ->
                textViewSelectedFileName = null // onDismissでもリセットされるが、明示的に
                dialog.cancel()
            }
            .setOnDismissListener {
                textViewSelectedFileName = null
            }
            .show()
    }

    private fun performCommentSubmission() {
        if (isSubmissionProcessActive) {
            Toast.makeText(this, "既に処理を実行中です。", Toast.LENGTH_SHORT).show()
            return
        }
        if (targetPageUrlForFields == null || currentBoardId == null) {
            resetSubmissionState("送信情報が不足しています(URL/ID)。")
            return
        }
        Log.d("DetailActivity", "Submitting to board ID: $currentBoardId, from URL: $currentUrl, target page for fields: $targetPageUrlForFields")

        initializeBackgroundWebView() // 呼び出しを追加
        isSubmissionProcessActive = true
        currentHiddenFieldStep = 0
        hiddenFormValues.clear()
        backgroundWebView?.loadUrl(targetPageUrlForFields!!)
        Toast.makeText(this, "投稿準備中です...", Toast.LENGTH_SHORT).show()
    }

    private fun sendCommentWithOkHttp() {
        if (!isSubmissionProcessActive) {
            resetSubmissionState("送信プロセスが予期せず非アクティブになりました。")
            return
        }

        val name = commentFormDataMap[KEY_NAME] as? String ?: ""
        val email = commentFormDataMap[KEY_EMAIL] as? String ?: ""
        val subject = commentFormDataMap[KEY_SUBJECT] as? String ?: ""
        val comment = commentFormDataMap[KEY_COMMENT] as? String ?: ""
        val currentFileUri = commentFormDataMap[KEY_SELECTED_FILE_URI] as? Uri // ここではプロパティ/引数と同じ名前でOK

        val multipartBodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("mode", "regist")
            .addFormDataPart("MAX_FILE_SIZE", maxFileSizeBytes.toString()) // サーバー側の期待値に合わせる、String型で
            .addFormDataPart("js", "on")
            .addFormDataPart("pwd", "")

        currentBoardId?.let { multipartBodyBuilder.addFormDataPart("resto", it) }
        multipartBodyBuilder.addFormDataPart("name", name)
        multipartBodyBuilder.addFormDataPart("email", email)
        multipartBodyBuilder.addFormDataPart("sub", subject) // `sub` が予約語等でなければOK
        multipartBodyBuilder.addFormDataPart("com", comment)
        hiddenFormValues.forEach { (key, value) -> multipartBodyBuilder.addFormDataPart(key, value) }
        if (currentFileUri == null) {
            multipartBodyBuilder.addFormDataPart("textonly", "on")
        }

        currentFileUri?.let { localUri -> // ローカル変数をキャプチャ
            val fileName = getFileName(localUri) ?: "attachment_${System.currentTimeMillis()}" // fileName はここで使用
            val mediaTypeString = contentResolver.getType(localUri)
            val mediaType = mediaTypeString?.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()
            try {
                contentResolver.openInputStream(localUri)?.use { inputStream ->
                    val fileBytes = inputStream.readBytes()
                    val fileRequestBody = fileBytes.toRequestBody(mediaType, 0, fileBytes.size)
                    multipartBodyBuilder.addFormDataPart("upfile", fileName, fileRequestBody) // fileName を使用
                } ?: run {
                    Log.e("DetailActivity", "Failed to open InputStream for URI: $localUri")
                    resetSubmissionState("選択されたファイルを開けませんでした。")
                    return@sendCommentWithOkHttp // ラベル指定のreturn
                }
            } catch (e: IOException) { // e はここで宣言・使用
                Log.e("DetailActivity", "File reading error for OkHttp", e)
                resetSubmissionState("ファイルの読み込み中にエラーが発生しました: ${e.message}")
                return
            } catch (e: SecurityException) { // e はここで宣言・使用
                Log.e("DetailActivity", "File permission error for OkHttp", e)
                resetSubmissionState("ファイルへのアクセス許可がありません: ${e.message}")
                return
            }
        }

        val requestBody = multipartBodyBuilder.build()
        val webViewCookieManager = android.webkit.CookieManager.getInstance()
        val cookieUrlForRequest = targetPageUrlForFields ?: currentUrl ?: "" // 変数名を明確に
        val cookiesForRequest = webViewCookieManager.getCookie(cookieUrlForRequest) // 変数名を明確に
        val requestBuilder = Request.Builder()
            .url("https://may.2chan.net/b/futaba.php?guid=on")
            .post(requestBody)

        if (!cookiesForRequest.isNullOrEmpty()) { // nullチェックとemptyチェック
            requestBuilder.addHeader("Cookie", cookiesForRequest)
            Log.d("DetailActivity", "WebView Cookies added to OkHttp request: $cookiesForRequest")
        } else {
            Log.d("DetailActivity", "No WebView cookies found for OkHttp request for URL: $cookieUrlForRequest")
        }

        val request = requestBuilder.build()
        runOnUiThread { Toast.makeText(this, "コメントを送信中です...", Toast.LENGTH_SHORT).show() }
        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { // e はここで宣言・使用
                Log.e("DetailActivity", "OkHttp Submission Error: ${e.message}", e)
                runOnUiThread { resetSubmissionState("送信に失敗しました (ネットワークエラー): ${e.message}") }
            }

            override fun onResponse(call: Call, response: Response) {
                // responseBodyString はここで宣言・使用
                val responseBodyString = try { response.body?.string() } catch (e: Exception) { Log.e("DetailActivity", "Error reading response body",e); null }
                response.use { resp -> // `it` の代わりに `resp` を使用
                    if (resp.isSuccessful) {
                        Log.d("DetailActivity", "OkHttp Submission Success: Code=${resp.code}")

                        if (responseBodyString != null) {
                            val chunkSize = 1000
                            if (responseBodyString.length > chunkSize) {
                                Log.d("DetailActivity", "Response Body (chunked):")
                                for (i in 0..responseBodyString.length step chunkSize) {
                                    val end = minOf(i + chunkSize, responseBodyString.length)
                                    Log.d("DetailActivity", responseBodyString.substring(i, end))
                                }
                            } else {
                                Log.d("DetailActivity", "Response Body: $responseBodyString")
                            }
                        } else {
                            Log.d("DetailActivity", "Response Body is null")
                        }

                        if (responseBodyString?.contains("秒、投稿できません", ignoreCase = true) == true) {
                            var waitTimeMessage = "連続投稿制限のため、しばらく投稿できません。"
                            try {
                                val regex = Regex("""あと(\\d+)秒、投稿できません""")
                                val matchResult = responseBodyString.let { body -> if (body != null) regex.find(body) else null }
                                if (matchResult != null && matchResult.groupValues.size > 1) {
                                    val seconds = matchResult.groupValues[1].toInt() // seconds はここで宣言・使用
                                    val minutes = seconds / 60 // minutes はここで宣言・使用 (整数除算)
                                    waitTimeMessage = if (minutes > 0) {
                                        "連続投稿制限のため、あと約${minutes}分お待ちください。"
                                    } else {
                                        "連続投稿制限のため、あと${seconds}秒お待ちください。"
                                    }
                                }
                            } catch (e: Exception) { // e はここで宣言・使用
                                Log.w("DetailActivity", "Failed to parse wait time from cooldown message", e)
                            }
                            runOnUiThread {
                                resetSubmissionState(waitTimeMessage)
                            }
                            return@onResponse
                        }
                        else if (responseBodyString?.contains("あなたのipアドレスからは投稿できません", ignoreCase = true) == true) {
                            runOnUiThread {
                                resetSubmissionState("あなたのIPアドレスからは投稿できません。接続環境を変更して試すか、しばらく時間をおいてください。")
                            }
                            return@onResponse
                        }
                        else if (responseBodyString?.contains("cookieを有効にしてください", ignoreCase = true) == true) {
                            runOnUiThread {
                                resetSubmissionState("Cookieが無効か、または不足しています。設定を確認し、再度お試しください。")
                            }
                            return@onResponse
                        }
                        else if (responseBodyString?.contains("ERROR:", ignoreCase = true) == true ||
                            responseBodyString?.contains("エラー：", ignoreCase = true) == true ||
                            responseBodyString?.contains("<link rel=\"canonical\" href=\"https://may.2chan.net/b/futaba.htm\">", ignoreCase = true) == true ) {
                            var extractedErrorMessage = "サーバーが投稿を拒否しました。または予期せぬページが返されました。"
                            if (!responseBodyString.isNullOrEmpty()) {
                                if (responseBodyString.contains("ERROR:") || responseBodyString.contains("エラー：")) {
                                    val extracted = responseBodyString.substringAfter("<h1>", "").substringBefore("</h1>", "").trim()
                                    if (extracted.isNotEmpty()) extractedErrorMessage = extracted
                                    else {
                                        val extracted2 = responseBodyString.substringAfter("<font color=\"#ff0000\"><b>", "").substringBefore("</b></font>", "").trim()
                                        if(extracted2.isNotEmpty()) extractedErrorMessage = extracted2
                                    }
                                } else if (responseBodyString.contains("<link rel=\"canonical\" href=\"https://may.2chan.net/b/futaba.htm\">", ignoreCase = true)) {
                                    extractedErrorMessage = "投稿が受理されず、メインページが返されました。内容を確認してください。"
                                }
                            }
                            runOnUiThread { resetSubmissionState(extractedErrorMessage) }
                            return@onResponse
                        }
                        runOnUiThread {
                            Toast.makeText(this@DetailActivity, "コメントを送信しました。", Toast.LENGTH_LONG).show()
                            commentFormDataMap.clear()
                            selectedFileUri = null
                            resetSubmissionState()
                            currentUrl?.let { urlToRefresh ->
                                Toast.makeText(this@DetailActivity, "スレッドを再読み込みします...", Toast.LENGTH_SHORT).show()
                                viewModel.fetchDetails(urlToRefresh, forceRefresh = true) // キャッシュ更新
                            }
                        }
                    } else {
                        Log.w("DetailActivity", "OkHttp Submission Failed: Code=${resp.code}")
                        var errorMessage = "送信に失敗しました (サーバーエラーコード: ${resp.code})"
                        if (responseBodyString != null) {
                            val chunkSize = 1000
                            Log.w("DetailActivity", "Failed Response Body (chunked):")
                            for (i in 0..responseBodyString.length step chunkSize) {
                                val end = minOf(i + chunkSize, responseBodyString.length)
                                Log.w("DetailActivity", responseBodyString.substring(i, end))
                            }
                        }
                        if (!responseBodyString.isNullOrEmpty()) {
                            if (responseBodyString.contains("cookieを有効にしてください", ignoreCase = true) == true) {
                                errorMessage = "Cookieが無効か、または不足しています。設定を確認し、再度お試しください。"
                            } else if (responseBodyString.contains("ERROR:") || responseBodyString.contains("エラー：")) {
                                val extracted = responseBodyString.substringAfter("<h1>", "").substringBefore("</h1>", "").trim()
                                if (extracted.isNotEmpty()) errorMessage = extracted
                                else {
                                    val extracted2 = responseBodyString.substringAfter("<font color=\"#ff0000\"><b>", "").substringBefore("</b></font>", "").trim()
                                    if(extracted2.isNotEmpty()) errorMessage = extracted2
                                }
                            }
                        }
                        runOnUiThread { resetSubmissionState(errorMessage) }
                    }
                }
            }
        })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.detail_menu, menu)
        val searchItem = menu.findItem(R.id.action_search)
        searchView = searchItem?.actionView as? SearchView
        searchView?.queryHint = getString(R.string.search_hint)
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let { performSearch(it) }
                searchView?.clearFocus()
                return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                if (newText.isNullOrEmpty() && currentSearchQuery != null) { clearSearchAndUI() }
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
                    viewModel.fetchDetails(urlToRefresh, forceRefresh = true)
                    Toast.makeText(this, "再読み込みしています...", Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_write -> { showAddCommentDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onPause() {
        super.onPause()
        saveCurrentScrollStateIfApplicable()
    }

    override fun onDestroy() {
        backgroundWebView?.stopLoading()
        backgroundWebView?.destroy()
        backgroundWebView = null
        super.onDestroy()
    }

    private fun saveCurrentScrollStateIfApplicable() {
        if (searchView?.isIconified != false && currentUrl != null) { // isIconifiedのチェック条件を維持 (検索中でない場合)
            saveCurrentScrollState(currentUrl!!)
        }
    }

    private fun setupRecyclerView() {
        detailAdapter = DetailAdapter() // DetailAdapterのインスタンス化
        layoutManager = LinearLayoutManager(this@DetailActivity) // layoutManagerのインスタンス化
        detailAdapter.onQuoteClickListener = customLabel@{ quotedText ->
            val contentList = viewModel.detailContent.value ?: return@customLabel
            val targetPosition = contentList.indexOfFirst { content ->
                when (content) {
                    is DetailContent.Text -> Html.fromHtml(content.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString().contains(quotedText, ignoreCase = true)
                    is DetailContent.Image -> {
                        (content.fileName?.equals(quotedText, ignoreCase = true) == true ||
                         content.imageUrl.substringAfterLast('/').equals(quotedText, ignoreCase = true) ||
                         content.prompt?.contains(quotedText, ignoreCase = true) == true)
                    }
                    is DetailContent.Video -> {
                        (content.fileName?.equals(quotedText, ignoreCase = true) == true ||
                         content.videoUrl.substringAfterLast('/').equals(quotedText, ignoreCase = true) ||
                         content.prompt?.contains(quotedText, ignoreCase = true) == true)
                    }
                }
            }
            if (targetPosition != -1) {
                binding.detailRecyclerView.smoothScrollToPosition(targetPosition)
            } else {
                Toast.makeText(this, "引用元が見つかりません: $quotedText", Toast.LENGTH_SHORT).show()
            }
        }
        // ★★★ ここから「そうだね」リスナーを設定 ★★★
        detailAdapter.onSodaNeClickListener = { resNum ->
            viewModel.postSodaNe(resNum)
        }
        // ★★★ ここまで追加 ★★★
        binding.detailRecyclerView.apply {
            layoutManager = this@DetailActivity.layoutManager // より一般的な書き方に変更
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
            val hadPreviousContent = detailAdapter.itemCount > 0
            detailAdapter.submitList(contentList) {
                if (contentList.isNotEmpty()) {
                    currentUrl?.let { url ->
                        if (currentSearchQuery != null && searchView?.isIconified == false) {
                            performSearch(currentSearchQuery!!)
                        } else {
                            // スクロール位置復元の条件
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
                } else { clearSearchAndUI() }
            }
        }
        viewModel.error.observe(this) { errorMessage -> // errorMessage はここで使用
            if (!errorMessage.isNullOrEmpty()) {
                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
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

    private fun performSearch(query: String) {
        currentSearchQuery = query
        detailAdapter.setSearchQuery(query) // Adapterに検索クエリを渡す
        searchResultPositions.clear()
        currentSearchHitIndex = -1
        val contentList = viewModel.detailContent.value ?: return
        contentList.forEachIndexed { index, content ->
            val textToSearch: String? = when (content) {
                is DetailContent.Text -> Html.fromHtml(content.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
                is DetailContent.Image -> {
                    // プロンプト、ファイル名、URLのファイル名部分を検索対象にする
                    "${content.prompt ?: ""} ${content.fileName ?: ""} ${content.imageUrl.substringAfterLast('/')}"
                }
                is DetailContent.Video -> {
                    // プロンプト、ファイル名、URLのファイル名部分を検索対象にする
                    "${content.prompt ?: ""} ${content.fileName ?: ""} ${content.videoUrl.substringAfterLast('/')}"
                }
            }
            if (textToSearch?.contains(query, ignoreCase = true) == true) {
                searchResultPositions.add(index)
            }
        }
        if (searchResultPositions.isNotEmpty()) {
            currentSearchHitIndex = 0
            navigateToCurrentHit()
            binding.searchNavigationControls.visibility = View.VISIBLE
        } else {
            Toast.makeText(this, getString(R.string.no_results_found), Toast.LENGTH_SHORT).show()
            binding.searchNavigationControls.visibility = View.GONE
        }
        updateSearchResultsCount()
    }

    private fun clearSearchAndUI() {
        currentSearchQuery = null
        detailAdapter.setSearchQuery(null) // Adapterの検索クエリもクリア
        searchResultPositions.clear()
        currentSearchHitIndex = -1
        binding.searchNavigationControls.visibility = View.GONE
        updateSearchResultsCount()
        if (searchView?.isIconified == false) {
            searchView?.setQuery("", false)
            searchView?.isIconified = true
        }
        if (viewModel.isLoading.value == false) { // isLoadingがfalseの時のみ表示
            binding.detailRecyclerView.isVisible = true
        }
    }

    private fun navigateToCurrentHit() {
        if (searchResultPositions.isNotEmpty() && currentSearchHitIndex in searchResultPositions.indices) {
            val position = searchResultPositions[currentSearchHitIndex]
            if (position >= 0 && position < detailAdapter.itemCount) {
                binding.detailRecyclerView.post { // UIスレッドで実行
                    layoutManager.scrollToPositionWithOffset(position, 20) // 微調整オフセット
                }
            }
        }
        updateSearchResultsCount()
    }

    private fun setupSearchNavigation() {
        binding.searchUpButton.setOnClickListener {
            if (searchResultPositions.isNotEmpty()) {
                currentSearchHitIndex--
                if (currentSearchHitIndex < 0) { currentSearchHitIndex = searchResultPositions.size - 1 }
                navigateToCurrentHit()
            }
        }
        binding.searchDownButton.setOnClickListener {
            if (searchResultPositions.isNotEmpty()) {
                currentSearchHitIndex++
                if (currentSearchHitIndex >= searchResultPositions.size) { currentSearchHitIndex = 0 }
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

    private fun getFileName(uri: Uri): String? {
        var fileName: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex != -1) {
                        fileName = cursor.getString(displayNameIndex)
                    }
                }
            } // useブロックがここで閉じる
        } catch (e: Exception) {
            Log.e("DetailActivity", "Error getting file name from ContentResolver", e)
        }
        if (fileName == null) {
            fileName = uri.path?.substringAfterLast('/')
        }
        return fileName
    }

    private fun getFileSize(uri: Uri): Long? {
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> return pfd.statSize }
        } catch (e: Exception) {
            // エラーログのuri変数をローカルスコープのものに修正
            Log.e("DetailActivity", "Error getting file size for URI: $uri", e)
        }
        return null
    }
}
