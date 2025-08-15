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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull // ★★★ 修正: 非推奨APIの代わりにこちらを使用 ★★★
import okhttp3.MediaType.Companion.toMediaTypeOrNull // sendCommentWithOkHttp で使用
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody // sendCommentWithOkHttp で使用
import java.io.IOException // sendCommentWithOkHttp で使用
// import java.text.DecimalFormat // 未使用のためコメントアウト
import java.util.concurrent.TimeUnit
// import okhttp3.JavaNetCookieJar // Using custom CookieJar for OkHttp now
import android.app.AlertDialog // showAddCommentDialogで使用
import java.nio.charset.Charset // Shift_JIS対応のため
import java.util.ArrayDeque // スクロール履歴で使用

class DetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding
    private val viewModel: DetailViewModel by viewModels()
    private lateinit var detailAdapter: DetailAdapter // setupRecyclerViewで使用

    private lateinit var scrollPositionStore: ScrollPositionStore
    private var currentUrl: String? = null

    private lateinit var layoutManager: LinearLayoutManager // setupRecyclerViewで使用

    // Scroll history for "back" functionality
    private val scrollHistory = ArrayDeque<Pair<Int, Int>>(2) // Pair of position and offset

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

    // Custom CookieJar for OkHttpClient
    private val okHttpCookieJar = object : CookieJar {
        private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            val currentCookies = cookieStore.getOrPut(host) { mutableListOf() }
            // Remove old cookies that match the new ones by name
            cookies.forEach { newCookie ->
                currentCookies.removeAll { it.name == newCookie.name }
            }
            currentCookies.addAll(cookies)
            Log.d("DetailActivity_OkHttpCookieJar", "Saved cookies for $host: ${cookieStore[host]}")

            // Attempt to sync to WebView's CookieManager as well
            val webViewCookieManager = android.webkit.CookieManager.getInstance()
            cookies.forEach { cookie ->
                val cookieString = cookie.toString()
                Log.d("DetailActivity_OkHttpCookieJar", "Syncing to WebView CookieManager: $cookieString for $url")
                webViewCookieManager.setCookie(url.toString(), cookieString)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                webViewCookieManager.flush()
            } else {
                // For older versions, sync might be less reliable or require different methods
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val host = url.host
            val storedCookies = cookieStore[host] ?: emptyList()
            // Filter out expired cookies (simplified check)
            val validCookies = storedCookies.filter { it.expiresAt > System.currentTimeMillis() }
            Log.d("DetailActivity_OkHttpCookieJar", "Loading cookies for $host (found ${validCookies.size}/${storedCookies.size}): $validCookies")
            return validCookies
        }
    }

    private val okHttpClient = OkHttpClient.Builder()
        .cookieJar(okHttpCookieJar) // Use our custom CookieJar
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // .addNetworkInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.HEADERS }) // Optional: for deeper OkHttp logging
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

                            // Transfer cookies from WebView's CookieManager to OkHttp's CookieJar
                            if (!cookiesHeaderFromWebView.isNullOrEmpty()) {
                                // ★★★ 修正: HttpUrl.get(url!!) を url!!.toHttpUrlOrNull() に変更 ★★★
                                val httpUrl = url!!.toHttpUrlOrNull()
                                if (httpUrl != null) {
                                    cookiesHeaderFromWebView.split(";").forEach { cookieString ->
                                        Cookie.parse(httpUrl, cookieString.trim())?.let { parsedCookie ->
                                            Log.d("DetailActivity", "Transferring to OkHttpCookieJar: $parsedCookie from WebView for host ${httpUrl.host}")
                                            okHttpCookieJar.saveFromResponse(httpUrl, listOf(parsedCookie))
                                        }
                                    }
                                } else {
                                    Log.w("DetailActivity", "Could not parse URL for cookie transfer: $url")
                                }
                            } else {
                                Log.d("DetailActivity", "No cookies found in WebView CookieManager for $url")
                            }

                            currentHiddenFieldStep = 0
                            hiddenFormValues.clear()
                            executeHiddenFieldJsStep(view)
                        } else if (isSubmissionProcessActive && url != targetPageUrlForFields) {
                            Log.w("DetailActivity", "WebView navigated to unexpected URL ('$url') during submission, expecting '$targetPageUrlForFields'")
                            resetSubmissionState("ページ準備中に予期せぬURLに遷移: $url")
                        }
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        Log.e("DetailActivity", "WebView onReceivedError: ${error?.description} for ${request?.url}, isForMainFrame: ${request?.isForMainFrame}")
                        if (request?.isForMainFrame == true && request.url.toString() == targetPageUrlForFields) {
                            resetSubmissionState("ページの読み込みに失敗: ${error?.description ?: "不明なエラー"}")
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
            resetSubmissionState("WebViewが利用できません。")
            return
        }
        if (currentHiddenFieldStep < hiddenFieldSelectors.size) {
            val fieldName = hiddenFieldSelectors[currentHiddenFieldStep]
            // Ensure single quotes in JS are properly escaped for Kotlin string literals
            val jsToExecute = "(function() { var el = document.querySelector('input[name=\\'${fieldName}\\']'); return el ? el.value : ''; })();"
            Log.d("DetailActivity", "Executing JS for hidden field: $fieldName, JS: $jsToExecute")
            webView.evaluateJavascript(jsToExecute) { result ->
                // Result might be JSON-encoded (e.g., "\"value\""), so remove surrounding quotes if present.
                val value = result?.trim()?.removeSurrounding("\"") ?: ""
                Log.d("DetailActivity", "Hidden field: '$fieldName' = '$value'") // ★★★ ESSENTIAL LOG ★★★
                hiddenFormValues[fieldName] = value
                currentHiddenFieldStep++
                executeHiddenFieldJsStep(webView)
            }
        } else {
            Log.d("DetailActivity", "All hidden fields collected: $hiddenFormValues") // ★★★ ESSENTIAL LOG ★★★
            sendCommentWithOkHttp()
        }
    }

    private fun resetSubmissionState(toastMessage: String? = null) {
        Log.d("DetailActivity", "Resetting submission state. Message: $toastMessage")
        isSubmissionProcessActive = false
        // currentHiddenFieldStep = 0; // Already reset before starting executeHiddenFieldJsStep
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
                targetPageUrlForFields = currentUrl // This should be the page where the form is
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
            resetSubmissionState("送信情報が不足しています(URL/ID)。")
            return
        }
        Log.d("DetailActivity", "Performing comment submission to board ID: $currentBoardId, from URL: $currentUrl, target page for fields: $targetPageUrlForFields")

        initializeBackgroundWebView() // Ensures WebView is ready
        isSubmissionProcessActive = true
        currentHiddenFieldStep = 0 // Reset step counter for hidden fields
        hiddenFormValues.clear()   // Clear any previous hidden field values

        backgroundWebView?.let {
            Log.d("DetailActivity", "Loading URL in backgroundWebView for hidden fields: $targetPageUrlForFields")
            it.loadUrl(targetPageUrlForFields!!) // Load the page to extract hidden fields
            Toast.makeText(this, "投稿準備中です...", Toast.LENGTH_SHORT).show()
        } ?: run {
            Log.e("DetailActivity", "performCommentSubmission: backgroundWebView is null before loading URL.")
            resetSubmissionState("WebViewの初期化に失敗しました。")
        }
    }

    private fun sendCommentWithOkHttp() {
        if (!isSubmissionProcessActive) {
            Log.w("DetailActivity", "sendCommentWithOkHttp: Submission process not active.")
            resetSubmissionState("送信プロセスが予期せず非アクティブになりました。")
            return
        }
        Log.d("DetailActivity", "sendCommentWithOkHttp: Starting actual submission.")

        val name = commentFormDataMap[KEY_NAME] as? String ?: ""
        val email = commentFormDataMap[KEY_EMAIL] as? String ?: ""
        val subject = commentFormDataMap[KEY_SUBJECT] as? String ?: ""
        val comment = commentFormDataMap[KEY_COMMENT] as? String ?: ""
        val currentFileUri = commentFormDataMap[KEY_SELECTED_FILE_URI] as? Uri

        val multipartBodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("mode", "regist")
            .addFormDataPart("MAX_FILE_SIZE", maxFileSizeBytes.toString())
            .addFormDataPart("js", "on") // Assume JS is enabled
            .addFormDataPart("pwd", "")   // Password, if any

        currentBoardId?.let { multipartBodyBuilder.addFormDataPart("resto", it) }
        multipartBodyBuilder.addFormDataPart("name", name)
        multipartBodyBuilder.addFormDataPart("email", email)
        multipartBodyBuilder.addFormDataPart("sub", subject)
        multipartBodyBuilder.addFormDataPart("com", comment)

        Log.d("DetailActivity", "Using collected hiddenFormValues for submission: $hiddenFormValues")
        hiddenFormValues.forEach { (key, value) -> multipartBodyBuilder.addFormDataPart(key, value) }

        if (currentFileUri == null) {
            multipartBodyBuilder.addFormDataPart("textonly", "on")
        } else {
            // File attachment logic
            val fileName = getFileName(currentFileUri) ?: "attachment_${System.currentTimeMillis()}"
            val mediaTypeString = contentResolver.getType(currentFileUri)
            val mediaType = mediaTypeString?.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()
            try {
                contentResolver.openInputStream(currentFileUri)?.use { inputStream ->
                    val fileBytes = inputStream.readBytes()
                    val fileRequestBody = fileBytes.toRequestBody(mediaType, 0, fileBytes.size)
                    multipartBodyBuilder.addFormDataPart("upfile", fileName, fileRequestBody)
                    Log.d("DetailActivity", "Added file to multipart: $fileName, MediaType: $mediaType, Size: ${fileBytes.size}")
                } ?: run {
                    Log.e("DetailActivity", "Failed to open InputStream for URI: $currentFileUri")
                    resetSubmissionState("選択されたファイルを開けませんでした。")
                    return // Exit sendCommentWithOkHttp
                }
            } catch (e: IOException) {
                Log.e("DetailActivity", "File reading error for OkHttp", e)
                resetSubmissionState("ファイルの読み込み中にエラーが発生しました: ${e.message}")
                return // Exit sendCommentWithOkHttp
            } catch (e: SecurityException) {
                Log.e("DetailActivity", "File permission error for OkHttp", e)
                resetSubmissionState("ファイルへのアクセス許可がありません: ${e.message}")
                return // Exit sendCommentWithOkHttp
            }
        }

        val requestBody = multipartBodyBuilder.build()
        val submissionUrlString = "https://may.2chan.net/b/futaba.php?guid=on"
        // ★★★ 修正: HttpUrl.get(submissionUrlString) を submissionUrlString.toHttpUrlOrNull() に変更 ★★★
        val submissionHttpUrl = submissionUrlString.toHttpUrlOrNull()

        if (submissionHttpUrl == null) {
            Log.e("DetailActivity", "Could not parse submission URL: $submissionUrlString")
            resetSubmissionState("内部エラー: 送信URLの解析に失敗しました。")
            return
        }

        val requestBuilder = Request.Builder()
            .url(submissionHttpUrl)
            .post(requestBody)
            targetPageUrlForFields?.let { refererUrl ->
                requestBuilder.addHeader("Referer", refererUrl)
                Log.d("DetailActivity", "Added Referer header: $refererUrl")
            }
        // Cookie header is now automatically handled by OkHttp's CookieJar.
        // No need to manually add requestBuilder.addHeader("Cookie", cookiesForRequest)

        val cookiesForRequestHeader = okHttpCookieJar.loadForRequest(submissionHttpUrl)
        if (cookiesForRequestHeader.isNotEmpty()) {
            val cookieHeaderValue = cookiesForRequestHeader.joinToString(separator = "; ") { cookie ->
                // Cookieのname=value形式で結合。属性は含めないのが一般的。
                "${cookie.name}=${cookie.value}"
            }
            requestBuilder.addHeader("Cookie", cookieHeaderValue)
            Log.d("DetailActivity", "Manually added Cookie header: $cookieHeaderValue")
        } else {
            Log.d("DetailActivity", "No cookies found in CookieJar to add to header manually.")
        }

        val request = requestBuilder.build()
        Log.d("DetailActivity", "OkHttpRequest to be sent: Method=${request.method}, URL=${request.url}, Headers=${request.headers}") // ★★★ ESSENTIAL LOG ★★★
        // For very detailed header logging (if CookieJar content isn't immediately obvious in request.headers):
        // request.headers.forEach { header -> Log.d("DetailActivity", "OkHttpRequest Header: ${header.first}=${header.second}") }


        runOnUiThread { Toast.makeText(this, "コメントを送信中です...", Toast.LENGTH_SHORT).show() }
        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("DetailActivity", "OkHttp Submission Error: ${e.message}", e)
                runOnUiThread { resetSubmissionState("送信に失敗しました (ネットワークエラー): ${e.message}") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    Log.d("DetailActivity", "Response Code: ${resp.code}") // ★★★ ESSENTIAL LOG ★★★
                    Log.d("DetailActivity", "Response Headers:\n${resp.headers.toMultimap().entries.joinToString("\n") { "${it.key}: ${it.value.joinToString()}" }}") // ★★★ ESSENTIAL LOG (formatted) ★★★

                    val responseBytes = try { resp.body?.bytes() } catch (e: Exception) { Log.e("DetailActivity", "Error reading response bytes", e); null }
                    val responseBodyString = responseBytes?.toString(Charset.forName("Shift_JIS")) ?: "null or empty body"
                    Log.d("DetailActivity", "Response Body (Shift_JIS): $responseBodyString") // ★★★ ESSENTIAL LOG ★★★

                    if (resp.isSuccessful) {
                        Log.i("DetailActivity", "OkHttp Submission Success: Code=${resp.code}")
                        if (responseBodyString.contains("不正な参照元", ignoreCase = true)) {
                            runOnUiThread { resetSubmissionState("不正な参照元としてサーバーに拒否されました。Cookieやリクエスト内容を確認してください。") }
                            return@onResponse
                        } else if (responseBodyString.contains("秒、投稿できません", ignoreCase = true)) {
                            var waitTimeMessage = "連続投稿制限のため、しばらく投稿できません。"
                            try {
                                val regex = Regex("""あと(\d+)秒、投稿できません""")
                                val matchResult = regex.find(responseBodyString)
                                if (matchResult != null && matchResult.groupValues.size > 1) {
                                    val seconds = matchResult.groupValues[1].toInt()
                                    val minutes = seconds / 60
                                    waitTimeMessage = if (minutes > 0) {
                                        "連続投稿制限のため、あと約${minutes}分お待ちください。"
                                    } else {
                                        "連続投稿制限のため、あと${seconds}秒お待ちください。"
                                    }
                                }
                            } catch (e: Exception) { Log.w("DetailActivity", "Failed to parse wait time from cooldown message", e) }
                            runOnUiThread { resetSubmissionState(waitTimeMessage) }
                            return@onResponse
                        } else if (responseBodyString.contains("あなたのipアドレスからは投稿できません", ignoreCase = true)) {
                            runOnUiThread { resetSubmissionState("あなたのIPアドレスからは投稿できません。接続環境を変更して試すか、しばらく時間をおいてください。") }
                            return@onResponse
                        } else if (responseBodyString.contains("cookieを有効にしてください", ignoreCase = true)) {
                            runOnUiThread { resetSubmissionState("Cookieが無効か、または不足しています。設定を確認し、再度お試しください。") }
                            return@onResponse
                        } else if (responseBodyString.contains("ERROR:", ignoreCase = true) ||
                            responseBodyString.contains("エラー：", ignoreCase = true) ||
                            responseBodyString.contains("<link rel=\\\"canonical\\\" href=\\\"https://may.2chan.net/b/futaba.htm\\\">", ignoreCase = true) ) {
                            var extractedErrorMessage = "サーバーが投稿を拒否しました。または予期せぬページが返されました。"
                            if (responseBodyString.isNotEmpty()) {
                                if (responseBodyString.contains("ERROR:") || responseBodyString.contains("エラー：")) {
                                    val extracted = responseBodyString.substringAfter("<h1>", "").substringBefore("</h1>", "").trim()
                                    if (extracted.isNotEmpty()) extractedErrorMessage = extracted
                                    else {
                                        val extracted2 = responseBodyString.substringAfter("<font color=\\\"#ff0000\\\"><b>", "").substringBefore("</b></font>", "").trim()
                                        if(extracted2.isNotEmpty()) extractedErrorMessage = extracted2
                                    }
                                } else if (responseBodyString.contains("<link rel=\\\"canonical\\\" href=\\\"https://may.2chan.net/b/futaba.htm\\\">", ignoreCase = true)) {
                                    extractedErrorMessage = "投稿が受理されず、メインページが返されました。内容を確認してください。"
                                }
                            }
                            runOnUiThread { resetSubmissionState(extractedErrorMessage) }
                            return@onResponse
                        }
                        // If no specific error messages are found, assume success
                        runOnUiThread {
                            Toast.makeText(this@DetailActivity, "コメントを送信しました。", Toast.LENGTH_LONG).show()
                            commentFormDataMap.clear() // Clear form data on success
                            selectedFileUri = null     // Clear selected file URI
                            resetSubmissionState()     // Reset submission state (no message)
                            currentUrl?.let { urlToRefresh ->
                                Toast.makeText(this@DetailActivity, "スレッドを再読み込みします...", Toast.LENGTH_SHORT).show()
                                viewModel.fetchDetails(urlToRefresh, forceRefresh = true) // Refresh content
                            }
                        }
                    } else { // Not successful (e.g. 4xx, 5xx errors)
                        Log.w("DetailActivity", "OkHttp Submission Failed: Code=${resp.code}")
                        var errorMessage = "送信に失敗しました (サーバーエラーコード: ${resp.code})"
                        if (responseBodyString.isNotEmpty()) {
                            Log.w("DetailActivity", "Failed Response Body (Shift_JIS) for Code ${resp.code}:\n$responseBodyString")
                            // Check for specific error messages in failed responses too
                            if (responseBodyString.contains("不正な参照元", ignoreCase = true)) {
                                errorMessage = "不正な参照元としてサーバーに拒否されました (Code ${resp.code})。"
                            } else if (responseBodyString.contains("cookieを有効にしてください", ignoreCase = true)) {
                                errorMessage = "Cookieが無効か、または不足しています (Code ${resp.code})。"
                            } else if (responseBodyString.contains("ERROR:") || responseBodyString.contains("エラー：")) {
                                val extracted = responseBodyString.substringAfter("<h1>", "").substringBefore("</h1>", "").trim()
                                if (extracted.isNotEmpty()) errorMessage = extracted
                                else {
                                    val extracted2 = responseBodyString.substringAfter("<font color=\\\"#ff0000\\\"><b>", "").substringBefore("</b></font>", "").trim()
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
                    scrollHistory.clear()
                    viewModel.fetchDetails(urlToRefresh, forceRefresh = true)
                    Toast.makeText(this, "再読み込みしています...", Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(this, "戻る先の位置が無効です。", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "戻る履歴がありません。", Toast.LENGTH_SHORT).show()
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
        if (searchView?.isIconified != false && currentUrl != null) {
            saveCurrentScrollState(currentUrl!!)
        }
    }

    private fun setupRecyclerView() {
        detailAdapter = DetailAdapter()
        layoutManager = LinearLayoutManager(this@DetailActivity)
        detailAdapter.onQuoteClickListener = customLabel@{ quotedText ->
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
                    is DetailContent.ThreadEndTime -> false
                }
            }
            if (targetPosition != -1) {
                binding.detailRecyclerView.smoothScrollToPosition(targetPosition)
            } else {
                Toast.makeText(this, "引用元が見つかりません: $quotedText", Toast.LENGTH_SHORT).show()
            }
        }
        detailAdapter.onSodaNeClickListener = { resNum ->
            viewModel.postSodaNe(resNum)
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
        viewModel.error.observe(this) { errorMessage ->
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
        detailAdapter.setSearchQuery(query)
        searchResultPositions.clear()
        currentSearchHitIndex = -1
        val contentList = viewModel.detailContent.value ?: return
        contentList.forEachIndexed { index, content ->
            val textToSearch: String? = when (content) {
                is DetailContent.Text -> Html.fromHtml(content.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
                is DetailContent.Image -> "${content.prompt ?: ""} ${content.fileName ?: ""} ${content.imageUrl.substringAfterLast('/')}"
                is DetailContent.Video -> "${content.prompt ?: ""} ${content.fileName ?: ""} ${content.videoUrl.substringAfterLast('/')}"
                is DetailContent.ThreadEndTime -> null
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
        detailAdapter.setSearchQuery(null)
        searchResultPositions.clear()
        currentSearchHitIndex = -1
        binding.searchNavigationControls.visibility = View.GONE
        updateSearchResultsCount()
        if (searchView?.isIconified == false) {
            searchView?.setQuery("", false)
            searchView?.isIconified = true
        }
        if (viewModel.isLoading.value == false) {
            binding.detailRecyclerView.isVisible = true
        }
    }

    private fun navigateToCurrentHit() {
        if (searchResultPositions.isNotEmpty() && currentSearchHitIndex in searchResultPositions.indices) {
            val position = searchResultPositions[currentSearchHitIndex]
            if (position >= 0 && position < detailAdapter.itemCount) {
                binding.detailRecyclerView.post {
                    layoutManager.scrollToPositionWithOffset(position, 20)
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
                    } else {
                        Log.w("DetailActivity", "Column OpenableColumns.DISPLAY_NAME not found in getFileName.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DetailActivity", "Error getting file name from ContentResolver for URI $uri", e)
        }
        if (fileName == null) {
            // Fallback if ContentResolver query fails or doesn't provide a name
            fileName = uri.path?.substringAfterLast('/')
            if (fileName.isNullOrEmpty()) Log.w("DetailActivity", "Could not derive filename from URI path: $uri")
        }
        return fileName
    }

    private fun getFileSize(uri: Uri): Long? {
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> return pfd.statSize }
        } catch (e: Exception) {
            Log.e("DetailActivity", "Error getting file size for URI: $uri", e)
        }
        return null
    }
}