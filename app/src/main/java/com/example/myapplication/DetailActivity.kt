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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.charset.Charset
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

    private val okHttpCookieJar = object : CookieJar {
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
            // Lollipop (API 21) 以上では flush() が推奨される
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

    private val okHttpClient = OkHttpClient.Builder()
        .cookieJar(okHttpCookieJar)
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
        setupRecyclerView() // Defined in this class
        observeViewModel()  // Defined in this class
        viewModel.fetchDetails(currentUrl!!)
        detailSearchManager.setupSearchNavigation()

        filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                val fileSize = getFileSize(uri) // Defined in this class
                val fileNameToDisplay = getFileName(uri) // Defined in this class
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
                settings.javaScriptEnabled = true // XSS Warning: Review carefully
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
                        } else if (isSubmissionProcessActive && url != targetPageUrlForFields) { // This condition might always be true if the first one is false.
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
            val jsToExecute = "(function() { var el = document.querySelector('input[name=\'${fieldName}\']'); return el ? el.value : ''; })();"
            Log.d("DetailActivity", "Executing JS for hidden field: $fieldName, JS: $jsToExecute")
            webView.evaluateJavascript(jsToExecute) { result ->
                val valueResult = result?.trim()?.removeSurrounding("'''") ?: ""
                Log.d("DetailActivity", "Hidden field: '$fieldName' = '$valueResult'") // Use valueResult
                hiddenFormValues[fieldName] = valueResult
                currentHiddenFieldStep++
                executeHiddenFieldJsStep(webView)
            }
        } else {
            Log.d("DetailActivity", "All hidden fields collected: $hiddenFormValues")
            sendCommentWithOkHttp()
        }
    }

    private fun resetSubmissionState(message: String? = null) { // Renamed parameter to 'message'
        Log.d("DetailActivity", "Resetting submission state. Message: $message")
        isSubmissionProcessActive = false
        if (message != null) {
            runOnUiThread {
                Toast.makeText(this@DetailActivity, message, Toast.LENGTH_LONG).show()
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

        initializeBackgroundWebView()
        isSubmissionProcessActive = true
        currentHiddenFieldStep = 0
        hiddenFormValues.clear()

        backgroundWebView?.let {
            Log.d("DetailActivity", "Loading URL in backgroundWebView for hidden fields: $targetPageUrlForFields")
            it.loadUrl(targetPageUrlForFields!!)
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
        val currentFileUriForSubmission = commentFormDataMap[KEY_SELECTED_FILE_URI] as? Uri // Renamed to avoid confusion

        val multipartBodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("mode", "regist")
            .addFormDataPart("MAX_FILE_SIZE", maxFileSizeBytes.toString())
            .addFormDataPart("js", "on")
            .addFormDataPart("pwd", "")

        currentBoardId?.let { multipartBodyBuilder.addFormDataPart("resto", it) }
        multipartBodyBuilder.addFormDataPart("name", name)
        multipartBodyBuilder.addFormDataPart("email", email)
        multipartBodyBuilder.addFormDataPart("sub", subject)
        multipartBodyBuilder.addFormDataPart("com", comment)

        Log.d("DetailActivity", "Using collected hiddenFormValues for submission: $hiddenFormValues")
        hiddenFormValues.forEach { (key, value) -> multipartBodyBuilder.addFormDataPart(key, value) }

        if (currentFileUriForSubmission == null) {
            multipartBodyBuilder.addFormDataPart("textonly", "on")
        } else {
            val resolvedFileName = getFileName(currentFileUriForSubmission) ?: "attachment_${System.currentTimeMillis()}"
            val mediaTypeString = contentResolver.getType(currentFileUriForSubmission)
            val resolvedMediaType = mediaTypeString?.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()
            try {
                contentResolver.openInputStream(currentFileUriForSubmission)?.use { inputStream ->
                    val fileBytesArray = inputStream.readBytes() // Renamed
                    val fileRequestBody = fileBytesArray.toRequestBody(resolvedMediaType, 0, fileBytesArray.size)
                    multipartBodyBuilder.addFormDataPart("upfile", resolvedFileName, fileRequestBody)
                    Log.d("DetailActivity", "Added file to multipart: $resolvedFileName, MediaType: $resolvedMediaType, Size: ${fileBytesArray.size}")
                } ?: run {
                    Log.e("DetailActivity", "Failed to open InputStream for URI: $currentFileUriForSubmission")
                    resetSubmissionState("選択されたファイルを開けませんでした。")
                    return
                }
            } catch (e: IOException) {
                Log.e("DetailActivity", "File reading error for OkHttp", e)
                resetSubmissionState("ファイルの読み込み中にエラーが発生しました: ${e.message}")
                return
            } catch (e: SecurityException) {
                Log.e("DetailActivity", "File permission error for OkHttp", e)
                resetSubmissionState("ファイルへのアクセス許可がありません: ${e.message}")
                return
            }
        }

        val requestBody = multipartBodyBuilder.build()
        val currentSubmissionUrlString = "https://may.2chan.net/b/futaba.php?guid=on" // Renamed
        val submissionHttpUrl = currentSubmissionUrlString.toHttpUrlOrNull()

        if (submissionHttpUrl == null) {
            Log.e("DetailActivity", "Could not parse submission URL: $currentSubmissionUrlString")
            resetSubmissionState("内部エラー: 送信URLの解析に失敗しました。")
            return
        }

        val requestBuilder = Request.Builder()
            .url(submissionHttpUrl)
            .post(requestBody)
        val currentRefererUrl = targetPageUrlForFields // Renamed
            currentRefererUrl?.let {
                requestBuilder.addHeader("Referer", it)
                Log.d("DetailActivity", "Added Referer header: $it")
            }

        val cookiesForRequestHeader = okHttpCookieJar.loadForRequest(submissionHttpUrl)
        if (cookiesForRequestHeader.isNotEmpty()) {
            val builtCookieHeaderValue = cookiesForRequestHeader.joinToString(separator = "; ") { cookie -> // Renamed
                "${cookie.name}=${cookie.value}"
            }
            requestBuilder.addHeader("Cookie", builtCookieHeaderValue)
            Log.d("DetailActivity", "Manually added Cookie header: $builtCookieHeaderValue")
        } else {
            Log.d("DetailActivity", "No cookies found in CookieJar to add to header manually.")
        }

        val finalRequest = requestBuilder.build() // Renamed
        Log.d("DetailActivity", "OkHttpRequest to be sent: Method=${finalRequest.method}, URL=${finalRequest.url}, Headers=${finalRequest.headers}")

        runOnUiThread { Toast.makeText(this, "コメントを送信中です...", Toast.LENGTH_SHORT).show() }
        okHttpClient.newCall(finalRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("DetailActivity", "OkHttp Submission Error: ${e.message}", e)
                runOnUiThread { resetSubmissionState("送信に失敗しました (ネットワークエラー): ${e.message}") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { currentResponse -> // Renamed
                    Log.d("DetailActivity", "Response Code: ${currentResponse.code}")
                    val headersString = currentResponse.headers.toMultimap().entries.joinToString("\n") { entry -> "${entry.key}: ${entry.value.joinToString()}" }
                    Log.d("DetailActivity", "Response Headers:\n$headersString")

                    val responseBytes = try { currentResponse.body?.bytes() } catch (e: Exception) { Log.e("DetailActivity", "Error reading response bytes", e); null }
                    val currentResponseBodyString = responseBytes?.toString(Charset.forName("Shift_JIS")) ?: "null or empty body" // Renamed
                    Log.d("DetailActivity", "Response Body (Shift_JIS): $currentResponseBodyString")

                    if (currentResponse.isSuccessful) {
                        Log.i("DetailActivity", "OkHttp Submission Success: Code=${currentResponse.code}")
                        if (currentResponseBodyString.contains("不正な参照元", ignoreCase = true)) {
                            runOnUiThread { resetSubmissionState("不正な参照元としてサーバーに拒否されました。Cookieやリクエスト内容を確認してください。") }
                            return // Changed from return@onResponse
                        } else if (currentResponseBodyString.contains("秒、投稿できません", ignoreCase = true)) {
                            var waitTimeMessage = "連続投稿制限のため、しばらく投稿できません。"
                            try {
                                val regex = Regex("あと(\\d+)秒、投稿できません") // Changed from raw string to escaped string
                                val matchResult = regex.find(currentResponseBodyString)
                                if (matchResult != null && matchResult.groupValues.size > 1) {
                                    val waitSeconds = matchResult.groupValues[1].toInt() // Renamed
                                    val waitMinutes = waitSeconds / 60 // Renamed
                                    waitTimeMessage = if (waitMinutes > 0) {
                                        "連続投稿制限のため、あと約${waitMinutes}分お待ちください。"
                                    } else {
                                        "連続投稿制限のため、あと${waitSeconds}秒お待ちください。"
                                    }
                                }
                            } catch (e: Exception) { Log.w("DetailActivity", "Failed to parse wait time from cooldown message", e) }
                            runOnUiThread { resetSubmissionState(waitTimeMessage) }
                            return // Changed from return@onResponse
                        } else if (currentResponseBodyString.contains("あなたのipアドレスからは投稿できません", ignoreCase = true)) {
                            runOnUiThread { resetSubmissionState("あなたのIPアドレスからは投稿できません。接続環境を変更して試すか、しばらく時間をおいてください。") }
                            return // Changed from return@onResponse
                        } else if (currentResponseBodyString.contains("cookieを有効にしてください", ignoreCase = true)) {
                            runOnUiThread { resetSubmissionState("Cookieが無効か、または不足しています。設定を確認し、再度お試しください。") }
                            return // Changed from return@onResponse
                        } else if (currentResponseBodyString.contains("ERROR:", ignoreCase = true) ||
                            currentResponseBodyString.contains("エラー：", ignoreCase = true) ||
                            currentResponseBodyString.contains("<link rel=\"canonical\" href=\"https://may.2chan.net/b/futaba.htm\">", ignoreCase = true) ) {
                            var extractedErrorMessage = "サーバーが投稿を拒否しました。または予期せぬページが返されました。"
                            if (currentResponseBodyString.isNotEmpty()) {
                                if (currentResponseBodyString.contains("ERROR:") || currentResponseBodyString.contains("エラー：")) {
                                    val extracted = currentResponseBodyString.substringAfter("<h1>", "").substringBefore("</h1>", "").trim()
                                    if (extracted.isNotEmpty()) extractedErrorMessage = extracted
                                    else {
                                        val extracted2 = currentResponseBodyString.substringAfter("<font color=\"#ff0000\"><b>", "").substringBefore("</b></font>", "").trim()
                                        if(extracted2.isNotEmpty()) extractedErrorMessage = extracted2
                                    }
                                } else if (currentResponseBodyString.contains("<link rel=\"canonical\" href=\"https://may.2chan.net/b/futaba.htm\">", ignoreCase = true)) {
                                    extractedErrorMessage = "投稿が受理されず、メインページが返されました。内容を確認してください。"
                                }
                            }
                            runOnUiThread { resetSubmissionState(extractedErrorMessage) }
                            return // Changed from return@onResponse
                        }
                        runOnUiThread {
                            Toast.makeText(this@DetailActivity, "コメントを送信しました。", Toast.LENGTH_LONG).show()
                            commentFormDataMap.clear() 
                            selectedFileUri = null     
                            resetSubmissionState()     
                            currentUrl?.let { urlToRefresh ->
                                Toast.makeText(this@DetailActivity, "スレッドを再読み込みします...", Toast.LENGTH_SHORT).show()
                                viewModel.fetchDetails(urlToRefresh, forceRefresh = true) 
                            }
                        }
                    } else { 
                        Log.w("DetailActivity", "OkHttp Submission Failed: Code=${currentResponse.code}")
                        var errorMessage = "送信に失敗しました (サーバーエラーコード: ${currentResponse.code})"
                        if (currentResponseBodyString.isNotEmpty()) {
                            Log.w("DetailActivity", "Failed Response Body (Shift_JIS) for Code ${currentResponse.code}:\n$currentResponseBodyString")
                            if (currentResponseBodyString.contains("不正な参照元", ignoreCase = true)) {
                                errorMessage = "不正な参照元としてサーバーに拒否されました (Code ${currentResponse.code})。"
                            } else if (currentResponseBodyString.contains("cookieを有効にしてください", ignoreCase = true)) {
                                errorMessage = "Cookieが無効か、または不足しています (Code ${currentResponse.code})。"
                            } else if (currentResponseBodyString.contains("ERROR:") || currentResponseBodyString.contains("エラー：")) {
                                val extracted = currentResponseBodyString.substringAfter("<h1>", "").substringBefore("</h1>", "").trim()
                                if (extracted.isNotEmpty()) errorMessage = extracted
                                else {
                                    val extracted2 = currentResponseBodyString.substringAfter("<font color=\"#ff0000\"><b>", "").substringBefore("</b></font>", "").trim()
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
        if (!detailSearchManager.isSearchActive() && currentUrl != null) {
            saveCurrentScrollState(currentUrl!!)
        }
    }

    private fun setupRecyclerView() {
        detailAdapter = DetailAdapter() 
        layoutManager = LinearLayoutManager(this@DetailActivity)
        detailAdapter.onQuoteClickListener = customLabel@{ currentQuotedText -> // Renamed
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
                Toast.makeText(this, "引用元が見つかりません: $currentQuotedText", Toast.LENGTH_SHORT).show()
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

    private fun getFileName(fileUri: Uri): String? { // Renamed parameter
        var fileNameResult: String? = null // Renamed
        try {
            contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex != -1) {
                        fileNameResult = cursor.getString(displayNameIndex)
                    } else {
                        Log.w("DetailActivity", "Column OpenableColumns.DISPLAY_NAME not found in getFileName.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DetailActivity", "Error getting file name from ContentResolver for URI $fileUri", e)
        }
        if (fileNameResult == null) {
            fileNameResult = fileUri.path?.substringAfterLast('/')
            if (fileNameResult.isNullOrEmpty()) Log.w("DetailActivity", "Could not derive filename from URI path: $fileUri")
        }
        return fileNameResult
    }

    private fun getFileSize(fileUri: Uri): Long? { // Renamed parameter
        try {
            contentResolver.openFileDescriptor(fileUri, "r")?.use { pfd -> return pfd.statSize }
        } catch (e: Exception) {
            Log.e("DetailActivity", "Error getting file size for URI: $fileUri", e)
        }
        return null
    }

    // Implementation of SearchManagerCallback
    override fun getDetailContent(): List<DetailContent>? {
        return viewModel.detailContent.value
    }

    override fun getDetailAdapter(): DetailAdapter {
        return detailAdapter // Ensure detailAdapter is initialized before this is called
    }

    override fun getLayoutManager(): LinearLayoutManager {
        return layoutManager // Ensure layoutManager is initialized
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
        // This checks if 'binding' has been assigned.
        // It's a good practice for callbacks that might be invoked before full initialization.
        return ::binding.isInitialized 
    }
}
