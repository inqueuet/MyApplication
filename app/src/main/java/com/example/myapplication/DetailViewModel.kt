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

                // Clear previous content or indicate loading of new content for this URL
                _detailContent.postValue(emptyList())

                contentBlocks.forEach { block ->
                    // 1. Extract and add text content
                    val textBlock = block.clone()
                    val mediaLinkForTextExclusion = block.select("a[target=_blank]").firstOrNull { a ->
                        val href = a.attr("href").lowercase()
                        href.endsWith(".png") || href.endsWith(".jpg") || href.endsWith(".jpeg") || href.endsWith(".gif") || href.endsWith(".webp") || href.endsWith(".webm") || href.endsWith(".mp4")
                    }
                    mediaLinkForTextExclusion?.let { link -> textBlock.select("a[href='''${link.attr("href")}''']").remove() }
                    val html = textBlock.selectFirst(".rtd")?.html() ?: ""
                    if (html.isNotBlank()) {
                        progressivelyLoadedContent.add(DetailContent.Text(html))
                    }

                    // 2. Extract and add media content (placeholder for prompt)
                    val mediaLinkNode = block.select("a[target=_blank]").firstOrNull { a ->
                        val href = a.attr("href").lowercase()
                        href.endsWith(".png") || href.endsWith(".jpg") || href.endsWith(".jpeg") || href.endsWith(".gif") || href.endsWith(".webp") || href.endsWith(".webm") || href.endsWith(".mp4")
                    }

                    mediaLinkNode?.let { link ->
                        val href = link.attr("href").lowercase()
                        val absoluteUrl = URL(URL(url), href).toString()
                        val fileName = absoluteUrl.substringAfterLast('/')
                        val itemIndexInList = progressivelyLoadedContent.size // Index for the media item to be added

                        val mediaContent = when {
                            href.endsWith(".jpg") || href.endsWith(".png") || href.endsWith(".jpeg") || href.endsWith(".gif") || href.endsWith(".webp") -> {
                                DetailContent.Image(absoluteUrl, null, fileName)
                            }
                            href.endsWith(".webm") || href.endsWith(".mp4") -> {
                                DetailContent.Video(absoluteUrl, null, fileName)
                            }
                            else -> null
                        }

                        if (mediaContent != null) {
                            progressivelyLoadedContent.add(mediaContent)
                            val deferredPrompt = async(Dispatchers.IO) {
                                try {
                                    Pair(itemIndexInList, MetadataExtractor.extract(absoluteUrl))
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
                    // Post intermediate results after processing each block (or a batch of blocks)
                    _detailContent.postValue(progressivelyLoadedContent.toList())
                }

                _isLoading.value = false // Initial content (text + media placeholders) is loaded

                // Wait for all prompt jobs to complete for caching the final list
                val allPromptResults = promptJobs.awaitAll()

                val finalContentListForCache = progressivelyLoadedContent.toMutableList() // Start with the base list
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
                // Optionally, post the fully complete list again to ensure UI consistency,
                // though individual updates should ideally cover this.
                // _detailContent.postValue(finalContentListForCache.toList())


                withContext(Dispatchers.IO) {
                    cacheManager.saveDetails(url, finalContentListForCache.toList())
                }

            } catch (e: Exception) {
                _error.value = "詳細の取得に失敗しました: ${e.message}"
                e.printStackTrace()
                _isLoading.value = false
            }
            // No finally block for _isLoading.value = false, as it's handled by specific paths.
        }
    }

    fun invalidateCache(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            cacheManager.invalidateCache(url)
        }
    }

    /**
     * 「そうだね」を投稿する
     * @param resNum レス番号
     */
    fun postSodaNe(resNum: String) {
        val url = currentUrl
        if (url == null) {
            _error.value = "「そうだね」の投稿に失敗しました: 対象のURLが不明です。"
            _isLoading.value = false
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
