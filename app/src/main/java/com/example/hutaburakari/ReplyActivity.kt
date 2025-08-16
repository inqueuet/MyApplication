package com.example.hutaburakari

import android.annotation.SuppressLint
import android.app.Activity // Activity.RESULT_OK のために必要
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat // Added import
import androidx.core.view.isVisible
import com.example.hutaburakari.databinding.ActivityReplyBinding
import com.franmontiel.persistentcookiejar.PersistentCookieJar
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

@AndroidEntryPoint
class ReplyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReplyBinding
    @Inject
    lateinit var cookieJar: PersistentCookieJar

    private var threadId: String? = null
    private var threadTitle: String? = null
    private var boardUrl: String? = null
    private lateinit var webView: WebView
    private var finalTargetUrl: String = "" // Added to store the correctly formatted URL

    companion object {
        const val EXTRA_THREAD_ID = "extra_thread_id"
        const val EXTRA_THREAD_TITLE = "extra_thread_title"
        const val EXTRA_BOARD_URL = "extra_board_url"
        const val EXTRA_QUOTE_TEXT = "extra_quote_text" // ★ 追加
        private const val TAG = "ReplyActivity"
        private const val PC_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
    }

    // JavaScript Interface Class
    inner class WebAppInterface {
        @JavascriptInterface
        fun submissionSuccessful() {
            runOnUiThread {
                Toast.makeText(this@ReplyActivity, "投稿が成功したため画面を閉じます。", Toast.LENGTH_LONG).show()
                setResult(Activity.RESULT_OK) // 成功を通知
                finish()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false) // Added for edge-to-edge
        binding = ActivityReplyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        webView = binding.webView

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "レスを投稿 (WebView)"

        threadId = intent.getStringExtra(EXTRA_THREAD_ID)
        threadTitle = intent.getStringExtra(EXTRA_THREAD_TITLE)
        boardUrl = intent.getStringExtra(EXTRA_BOARD_URL)
        val quoteText = intent.getStringExtra(EXTRA_QUOTE_TEXT) // ★ 追加

        Log.d(TAG, "onCreate Received threadId: $threadId, threadTitle: $threadTitle, boardUrl: $boardUrl, quoteText: $quoteText") // ★ ログ更新

        if (threadId == null || boardUrl == null) {
            Log.e(TAG, "onCreate Error: Missing crucial data. threadId: $threadId, boardUrl: $boardUrl")
            Toast.makeText(this, "エラー: スレッド情報またはボードURLがありません", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.textViewThreadTitle.text = threadTitle ?: "タイトルなし"

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.userAgentString = PC_USER_AGENT
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING

        // Add JavaScript Interface
        webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")

        val webViewCookieManager = CookieManager.getInstance()
        webViewCookieManager.setAcceptCookie(true)
        webViewCookieManager.setAcceptThirdPartyCookies(webView, true)
        val futabaBaseUrl = boardUrl?.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: "https://may.2chan.net"
        val cookiesFromJar = cookieJar.loadForRequest(futabaBaseUrl.toHttpUrlOrNull()!!)
        if (cookiesFromJar.isNotEmpty()) {
            for (cookie in cookiesFromJar) {
                val cookieString = cookie.toString()
                webViewCookieManager.setCookie(futabaBaseUrl, cookieString)
                Log.d(TAG, "Copied cookie to WebView: $cookieString for $futabaBaseUrl")
            }
            webViewCookieManager.flush()
        } else {
            Log.d(TAG, "No cookies found in PersistentCookieJar for $futabaBaseUrl")
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.isVisible = true
                Log.d(TAG, "Page started loading: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.isVisible = false
                Log.d(TAG, "Page finished loading: $url")

                // Use the correctly formatted finalTargetUrl for comparison
                Log.d(TAG, "Checking if current URL '$url' matches expected '$finalTargetUrl'")

                if (url == finalTargetUrl) {
                    var scriptToExecute = """
                        javascript:(function() {
                            var formElement = document.getElementById('fm');
                            if (!formElement) { console.log('Form "fm" not found'); return; }

                            document.body.innerHTML = '';
                            document.body.appendChild(formElement);
                            formElement.style.padding = '16px';
                            formElement.style.margin = '0 auto';
                            formElement.style.boxSizing = 'border-box';
                            formElement.style.width = '100%';
                            document.body.style.padding = '0';
                            document.body.style.margin = '0';
                            document.body.style.backgroundColor = '#FFFFFF';
                            document.body.style.display = 'flex';
                            document.body.style.flexDirection = 'column';
                            document.body.style.alignItems = 'center';

                            var commentTextArea = formElement.querySelector('textarea#ftxa');
                            var submitButton = formElement.querySelector('input[type="submit"]');

                            if (!commentTextArea || !submitButton) { console.log('Required elements not found in form'); return; }
                    """

                    if (!quoteText.isNullOrEmpty()) {
                        // JavaScript内で安全に文字列を扱うためにエスケープする
                        val escapedQuoteText = quoteText
                            .replace("\\", "\\\\") // バックスラッシュ
                            .replace("'", "\\'")    // シングルクォート
                            .replace("\"", "\\\"")   // ダブルクォート
                            .replace("\n", "\\n")   // 改行
                            .replace("\r", "\\r")   // キャリッジリターン
                        scriptToExecute += """
                            if (commentTextArea) {
                                commentTextArea.value = '${escapedQuoteText}';
                                console.log('Quote text set to textarea.');
                            }
                        """
                    }

                    scriptToExecute += """
                            submitButton.addEventListener('click', function() {
                                var initialCommentValue = commentTextArea.value;
                                if (initialCommentValue !== "") {
                                    var pollInterval = 300; 
                                    var maxPollTime = 5000; 
                                    var elapsedTime = 0;
                                    var intervalId = setInterval(function() {
                                        elapsedTime += pollInterval;
                                        if (commentTextArea.value === '') { // 送信後は空になると想定
                                            clearInterval(intervalId);
                                            if (window.AndroidBridge && typeof window.AndroidBridge.submissionSuccessful === 'function') {
                                                window.AndroidBridge.submissionSuccessful();
                                            }
                                        } else if (elapsedTime >= maxPollTime) {
                                            clearInterval(intervalId);
                                            console.log('Polling timed out, textarea value did not change to empty.');
                                        }
                                    }, pollInterval);
                                }
                            });
                            console.log('Form isolated and submission listener attached.');
                        })()
                    """.trimIndent().replace("<caret>", " ") // <caret> はもしあれば削除
                    view?.evaluateJavascript(scriptToExecute, null)
                    Log.d(TAG, "JavaScript for form isolation, quote setting, and submission detection executed.")
                }

                val pageContentForCheck = view?.title ?: ""
                if (url?.contains("futaba.htm", ignoreCase = true) == true && 
                    (pageContentForCheck.contains("削除されました") || pageContentForCheck.contains("error", ignoreCase = true) || pageContentForCheck.contains("ＥＲＲＯＲ"))
                ) {
                    Toast.makeText(this@ReplyActivity, "投稿エラーが発生した可能性があります。", Toast.LENGTH_LONG).show()
                }

            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                binding.progressBar.isVisible = false
                val errorMsg = error?.description?.toString() ?: "WebView Error"
                Log.e(TAG, "WebView error: $errorMsg for URL: ${request?.url}")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                return super.onShowFileChooser(webView, filePathCallback, fileChooserParams)
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress < 100 && !binding.progressBar.isVisible) {
                    binding.progressBar.isVisible = true
                }
                if (newProgress == 100) {
                    binding.progressBar.isVisible = false
                }
            }
        }

        // Correctly format the target URL
        // boardUrl and threadId are confirmed not null at this point
        val baseBoardPath = boardUrl!!.substringBeforeLast("futaba.php", missingDelimiterValue = boardUrl!!)
        finalTargetUrl = "${baseBoardPath}res/${threadId!!}.htm"
        Log.d(TAG, "onCreate - Attempting to load URL in WebView: $finalTargetUrl")
        webView.loadUrl(finalTargetUrl)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        Log.d(TAG, "WebView onPause")
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        Log.d(TAG, "WebView onResume")
    }

    override fun onDestroy() {
        Log.d(TAG, "WebView onDestroy: Releasing WebView resources")
        webView.stopLoading()
        webView.settings.javaScriptEnabled = false
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    onBackPressedDispatcher.onBackPressed()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
