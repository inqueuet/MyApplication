package com.example.hutaburakari

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
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

    companion object {
        const val EXTRA_THREAD_ID = "extra_thread_id"
        const val EXTRA_THREAD_TITLE = "extra_thread_title"
        const val EXTRA_BOARD_URL = "extra_board_url"
        private const val TAG = "ReplyActivity"
        private const val PC_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
    }

    // JavaScript Interface Class
    inner class WebAppInterface {
        @JavascriptInterface
        fun submissionSuccessful() {
            runOnUiThread {
                Toast.makeText(this@ReplyActivity, "投稿が成功したため画面を閉じます。", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReplyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        webView = binding.webView

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "レスを投稿 (WebView)"

        threadId = intent.getStringExtra(EXTRA_THREAD_ID)
        threadTitle = intent.getStringExtra(EXTRA_THREAD_TITLE)
        boardUrl = intent.getStringExtra(EXTRA_BOARD_URL)

        Log.d(TAG, "onCreate Received threadId: $threadId, threadTitle: $threadTitle, boardUrl: $boardUrl")

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

                val expectedInitialUrl = "${this@ReplyActivity.boardUrl}res/${this@ReplyActivity.threadId}.htm"
                Log.d(TAG, "Checking if current URL '$url' matches expected '$expectedInitialUrl'")

                if (url == expectedInitialUrl) {
                    val javascript = """
                        javascript:(function() {
                            var formElement = document.getElementById('fm');
                            if (!formElement) { console.log('Form "fm" not found'); return; }

                            if (formElement.parentNode) { formElement.parentNode.removeChild(formElement); }
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

                            submitButton.addEventListener('click', function() {
                                var initialCommentValue = commentTextArea.value;
                                if (initialCommentValue !== "") {
                                    var pollInterval = 300; 
                                    var maxPollTime = 5000; 
                                    var elapsedTime = 0;
                                    var intervalId = setInterval(function() {
                                        elapsedTime += pollInterval;
                                        if (commentTextArea.value === '') {
                                            clearInterval(intervalId);
                                            if (window.AndroidBridge && typeof window.AndroidBridge.submissionSuccessful === 'function') {
                                                window.AndroidBridge.submissionSuccessful();
                                            }
                                        } else if (elapsedTime >= maxPollTime) {
                                            clearInterval(intervalId);
                                        }
                                    }, pollInterval);
                                }
                            });
                            console.log('Form isolated and submission listener attached.');
                        })()
                    """.trimIndent().replace("\n", " ")
                    view?.evaluateJavascript(javascript, null)
                    Log.d(TAG, "JavaScript for form isolation and submission detection executed.")
                }

                // Previous success/error detection based on URL and title is removed
                // as submission is now handled via JavaScriptInterface.
                // However, an error page might still navigate, so you might want to keep
                // a simplified error detection if errors cause navigation.
                // For now, focusing on the AJAX success case.
                val pageContentForCheck = view?.title ?: ""
                if (url?.contains("futaba.htm", ignoreCase = true) == true && // Check if it's an error page
                    (pageContentForCheck.contains("削除されました") || pageContentForCheck.contains("error", ignoreCase = true) || pageContentForCheck.contains("ＥＲＲＯＲ")) // Common error indicators
                ) {
                    Toast.makeText(this@ReplyActivity, "投稿エラーが発生した可能性があります。", Toast.LENGTH_LONG).show()
                    // Consider not finishing, or providing a way to retry/go back.
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
            // ... (onShowFileChooser, onProgressChanged as before)
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

        val targetUrl = "${boardUrl}res/${threadId}.htm"
        Log.d(TAG, "onCreate - Attempting to load URL in WebView: $targetUrl")
        webView.loadUrl(targetUrl)

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