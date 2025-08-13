package com.example.myapplication

import android.app.Application
import android.util.Log // ★ Logをインポート
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.myapplication.cache.DetailCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class DetailViewModel(application: Application) : AndroidViewModel(application) {

    private val _detailContent = MutableLiveData<List<DetailContent>>()
    val detailContent: LiveData<List<DetailContent>> = _detailContent

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val cacheManager = DetailCacheManager(application)
    private var currentUrl: String? = null // ★ 現在表示しているスレッドのURLを保持

    fun fetchDetails(url: String, forceRefresh: Boolean = false) {
        viewModelScope.launch {
            this@DetailViewModel.currentUrl = url // ★ URLを保存
            _isLoading.value = true
            _error.value = null

            try {
                if (!forceRefresh) {
                    val cachedDetails = withContext(Dispatchers.IO) {
                        cacheManager.loadDetails(url)
                    }
                    if (cachedDetails != null) {
                        _detailContent.postValue(cachedDetails)
                        _isLoading.value = false
                        return@launch
                    }
                }

                // ★ NetworkClient.fetchDocument を Dispatchers.IO で実行
                val document = withContext(Dispatchers.IO) {
                    NetworkClient.fetchDocument(getApplication(), url)
                }
                val contentBlocks = document.select("div.thre, table:has(td.rtd)")

                val pendingContent = contentBlocks.flatMap { block ->
                    val content = mutableListOf<Pair<String, Any>>()
                    val mediaLink = block.select("a[target=_blank]").firstOrNull { a ->
                        val href = a.attr("href").lowercase()
                        href.endsWith(".png") || href.endsWith(".jpg") || href.endsWith(".jpeg") || href.endsWith(".gif") || href.endsWith(".webp") || href.endsWith(".webm") || href.endsWith(".mp4")
                    }

                    val textBlock = block.clone()
                    mediaLink?.let { link -> textBlock.select("a[href='''${link.attr("href")}''']").remove() }
                    val html = textBlock.selectFirst(".rtd")?.html() ?: ""
                    if (html.isNotBlank()) {
                        content.add("text" to html)
                    }

                    mediaLink?.let { link ->
                        val href = link.attr("href").lowercase()
                        val absoluteUrl = URL(URL(url), href).toString()
                        when {
                            href.endsWith(".jpg") || href.endsWith(".png") || href.endsWith(".jpeg") || href.endsWith(".gif") || href.endsWith(".webp") -> {
                                content.add("image" to absoluteUrl)
                            }
                            href.endsWith(".webm") || href.endsWith(".mp4") -> {
                                content.add("video" to absoluteUrl)
                            }
                        }
                    }
                    content
                }

                val deferredPrompts = pendingContent.map { (type, data) ->
                    if ((type == "image" || type == "video") && data is String) {
                        async {
                            // ★ MetadataExtractor.extract を Dispatchers.IO で実行
                            withContext(Dispatchers.IO) {
                                MetadataExtractor.extract(data)
                            }
                        }
                    } else {
                        async { null }
                    }
                }
                val prompts = deferredPrompts.awaitAll()

                val finalContentList = pendingContent.mapIndexedNotNull { index, (type, data) ->
                    when (type) {
                        "text" -> DetailContent.Text(data as String)
                        "image" -> {
                            val imageUrl = data as String
                            DetailContent.Image(imageUrl, prompts[index], imageUrl.substringAfterLast('/'))
                        }
                        "video" -> {
                            val videoUrl = data as String
                            DetailContent.Video(videoUrl, prompts[index], videoUrl.substringAfterLast('/'))
                        }
                        else -> null
                    }
                }

                _detailContent.postValue(finalContentList)
                withContext(Dispatchers.IO) {
                    cacheManager.saveDetails(url, finalContentList)
                }

            } catch (e: Exception) {
                _error.value = "詳細の取得に失敗しました: ${e.message}"
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun invalidateCache(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            cacheManager.invalidateCache(url)
        }
    }

    // ★★★ ここから新しい関数を追加 ★★★
    /**
     * 「そうだね」を投稿する
     * @param resNum レス番号
     */
    fun postSodaNe(resNum: String) {
        val url = currentUrl
        if (url == null) {
            _error.value = "「そうだね」の投稿に失敗しました: 対象のURLが不明です。"
            _isLoading.value = false // ★ エラー時にもisLoadingをfalseに
            return
        }

        Log.d("DetailViewModel", "postSodaNe called. resNum: $resNum, currentUrl: $url")

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                Log.d("DetailViewModel", "Calling NetworkClient.postSodaNe with resNum: $resNum, referer: $url")
                val success = NetworkClient.postSodaNe(getApplication(), resNum, url)
                Log.d("DetailViewModel", "NetworkClient.postSodaNe result: $success")
                if (success) {
                    // 成功したら現在のスレッドを強制再読み込みして表示を更新
                    fetchDetails(url, forceRefresh = true)
                } else {
                    _error.value = "「そうだね」の投稿に失敗しました。"
                    _isLoading.value = false  // ★ 失敗時にもisLoadingをfalseに
                }
            } catch (e: Exception) {
                Log.e("DetailViewModel", "Error in postSodaNe: ${e.message}", e)
                _error.value = "「そうだね」の投稿中にエラーが発生しました: ${e.message}"
                _isLoading.value = false // ★ 例外発生時にもisLoadingをfalseに
            }
            // fetchDetailsが成功した場合、その中でisLoadingはfalseにされる
        }
    }
    // ★★★ ここまで追加 ★★★
}
