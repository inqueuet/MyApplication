package com.example.myapplication

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.net.URL

class DetailViewModel(application: Application) : AndroidViewModel(application) {

    private val _detailContent = MutableLiveData<List<DetailContent>>()
    val detailContent: LiveData<List<DetailContent>> = _detailContent

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    fun fetchDetails(url: String) {
        viewModelScope.launch {
            _isLoading.value = true // 読み込み開始
            try {
                val document = NetworkClient.fetchDocument(getApplication(), url)

                val contentBlocks = document.select("div.thre, table:has(td.rtd)")

                // 先に全てのコンテンツ候補をリストアップする
                val pendingContent = contentBlocks.flatMap { block ->
                    val content = mutableListOf<Pair<String, Any>>() // type, data
                    val mediaLink = block.select("a[target=_blank]").firstOrNull { a ->
                        val href = a.attr("href").lowercase()
                        href.endsWith(".png") || href.endsWith(".jpg") || href.endsWith(".jpeg") || href.endsWith(".gif") || href.endsWith(".webp") || href.endsWith(".webm") || href.endsWith(".mp4")
                    }

                    // 1. テキスト部分
                    val textBlock = block.clone()
                    mediaLink?.let { link -> textBlock.select("a[href='${link.attr("href")}']").remove() }
                    val html = textBlock.selectFirst(".rtd")?.html() ?: ""
                    if (html.isNotBlank()) {
                        content.add("text" to html)
                    }

                    // 2. メディア部分
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

                // メディアファイルのプロンプトを非同期で並列取得
                val deferredPrompts = pendingContent.map { (type, data) ->
                    if ((type == "image" || type == "video") && data is String) {
                        async { MetadataExtractor.extract(data) }
                    } else {
                        async { null } // テキストやその他はnull
                    }
                }
                val prompts = deferredPrompts.awaitAll()

                // 最終的なコンテンツリストを構築
                val finalContentList = pendingContent.mapIndexed { index, (type, data) ->
                    when(type) {
                        "text" -> DetailContent.Text(data as String)
                        "image" -> DetailContent.Image(data as String, prompts[index])
                        "video" -> DetailContent.Video(data as String, prompts[index])
                        else -> null
                    }
                }.filterNotNull()

                _detailContent.postValue(finalContentList)

            } catch (e: Exception) {
                _error.value = "詳細の取得に失敗しました: ${e.message}"
                e.printStackTrace()
            } finally {
                _isLoading.value = false // 読み込み終了
            }
        }
    }
}