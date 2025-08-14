package com.example.myapplication

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.myapplication.cache.DetailCacheManager
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.MalformedURLException
import java.net.URL

class DetailViewModel(application: Application) : AndroidViewModel(application) {

    private val _detailContent = MutableLiveData<List<DetailContent>>()
    val detailContent: LiveData<List<DetailContent>> = _detailContent

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val cacheManager = DetailCacheManager(application)
    private var currentUrl: String? = null

    fun fetchDetails(url: String, forceRefresh: Boolean = false) {
        viewModelScope.launch {
            this@DetailViewModel.currentUrl = url
            _isLoading.value = true
            _error.value = null
            var itemIdCounter = 0L // ID生成用のカウンターを追加

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

                // Network fetch starts
                val document = withContext(Dispatchers.IO) {
                    NetworkClient.fetchDocument(getApplication(), url)
                }
                val contentBlocks = document.select("div.thre, table:has(td.rtd)")

                val progressivelyLoadedContent = mutableListOf<DetailContent>()
                val promptJobs = mutableListOf<Deferred<Pair<Int, String?>>>()

                _detailContent.postValue(emptyList())

                contentBlocks.forEach { block ->
                    val textBlock = block.clone()
                    val mediaLinkForTextExclusion = block.select("a[target=_blank]").firstOrNull { a ->
                        val href = a.attr("href").lowercase()
                        href.endsWith(".png") || href.endsWith(".jpg") || href.endsWith(".jpeg") || href.endsWith(".gif") || href.endsWith(".webp") || href.endsWith(".webm") || href.endsWith(".mp4")
                    }
                    mediaLinkForTextExclusion?.let { link -> textBlock.select("a[href='''${link.attr("href")}''']").remove() }
                    val html = textBlock.selectFirst(".rtd")?.html() ?: ""
                    if (html.isNotBlank()) {
                        progressivelyLoadedContent.add(DetailContent.Text(id = "text_${itemIdCounter++}", htmlContent = html))
                    }

                    val mediaLinkNode = block.select("a[target=_blank]").firstOrNull { a ->
                        val href = a.attr("href").lowercase()
                        href.endsWith(".png") || href.endsWith(".jpg") || href.endsWith(".jpeg") || href.endsWith(".gif") || href.endsWith(".webp") || href.endsWith(".webm") || href.endsWith(".mp4")
                    }

                    mediaLinkNode?.let { link ->
                        val hrefAttr = link.attr("href")
                        val absoluteUrl = try {
                            URL(URL(url), hrefAttr).toString()
                        } catch (e: MalformedURLException) {
                            Log.e("DetailViewModel", "Failed to form absolute URL from base: $url and href: $hrefAttr", e)
                            hrefAttr // Fallback to hrefAttr if URL construction fails
                        }
                        val fileName = absoluteUrl.substringAfterLast('/')
                        val itemIndexInList = progressivelyLoadedContent.size

                        val mediaContent = when {
                            hrefAttr.lowercase().endsWith(".jpg") || hrefAttr.lowercase().endsWith(".png") || hrefAttr.lowercase().endsWith(".jpeg") || hrefAttr.lowercase().endsWith(".gif") || hrefAttr.lowercase().endsWith(".webp") -> {
                                DetailContent.Image(id = absoluteUrl, imageUrl = absoluteUrl, prompt = null, fileName = fileName)
                            }
                            hrefAttr.lowercase().endsWith(".webm") || hrefAttr.lowercase().endsWith(".mp4") -> {
                                DetailContent.Video(id = absoluteUrl, videoUrl = absoluteUrl, prompt = null, fileName = fileName)
                            }
                            else -> null
                        }

                        if (mediaContent != null) {
                            progressivelyLoadedContent.add(mediaContent)
                            val deferredPrompt = async(Dispatchers.IO) {
                                try {
                                    Pair(itemIndexInList, MetadataExtractor.extract(getApplication(), absoluteUrl))
                                } catch (e: Exception) {
                                    Log.w("DetailViewModel", "Failed to extract metadata for $absoluteUrl", e)
                                    Pair(itemIndexInList, null as String?)
                                }
                            }
                            promptJobs.add(deferredPrompt)

                            viewModelScope.launch {
                                try {
                                    val (idx, promptResult) = deferredPrompt.await()
                                    val currentListSnapshot = _detailContent.value?.toList() ?: emptyList()
                                    if (idx < currentListSnapshot.size) {
                                       val newListForUpdate = currentListSnapshot.toMutableList()
                                       val oldItem = newListForUpdate[idx]
                                       newListForUpdate[idx] = when (oldItem) {
                                           is DetailContent.Image -> oldItem.copy(prompt = promptResult)
                                           is DetailContent.Video -> oldItem.copy(prompt = promptResult)
                                           else -> oldItem
                                       }
                                       _detailContent.postValue(newListForUpdate)
                                    } else {
                                         Log.w("DetailViewModel", "Index $idx out of bounds for UI update. List size: ${currentListSnapshot.size}")
                                    }
                                } catch (e: Exception) {
                                     Log.e("DetailViewModel", "Error awaiting individual prompt for UI update", e)
                                }
                            }
                        }
                    }
                }
                _detailContent.postValue(progressivelyLoadedContent.toList())
                _isLoading.value = false

                val allPromptResults = promptJobs.awaitAll()

                val finalContentListForCache = progressivelyLoadedContent.toMutableList()
                allPromptResults.forEach { (index, prompt) ->
                    if (index < finalContentListForCache.size) {
                        val itemToUpdate = finalContentListForCache[index]
                        finalContentListForCache[index] = when (itemToUpdate) {
                            is DetailContent.Image -> itemToUpdate.copy(prompt = prompt)
                            is DetailContent.Video -> itemToUpdate.copy(prompt = prompt)
                            else -> itemToUpdate
                        }
                    }
                }
                // _detailContent.postValue(finalContentListForCache.toList()) // Already posted individual updates

                withContext(Dispatchers.IO) {
                    cacheManager.saveDetails(url, finalContentListForCache.toList())
                }

            } catch (e: Exception) {
                _error.value = "詳細の取得に失敗しました: ${e.message}"
                Log.e("DetailViewModel", "Error fetching details for $url", e)
                _isLoading.value = false
            }
        }
    }

    fun invalidateCache(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            cacheManager.invalidateCache(url)
        }
    }

    fun postSodaNe(resNum: String) {
        val url = currentUrl
        if (url == null) {
            _error.value = "「そうだね」の投稿に失敗しました: 対象のURLが不明です。"
            // _isLoading.value = false; //isLoading may not be true here, let UI decide
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
                    fetchDetails(url, forceRefresh = true)
                } else {
                    _error.value = "「そうだね」の投稿に失敗しました。"
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                Log.e("DetailViewModel", "Error in postSodaNe: ${e.message}", e)
                _error.value = "「そうだね」の投稿中にエラーが発生しました: ${e.message}"
                _isLoading.value = false
            }
        }
    }
}
