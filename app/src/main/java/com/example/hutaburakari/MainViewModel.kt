package com.example.hutaburakari

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _images = MutableLiveData<List<ImageItem>>()
    val images: LiveData<List<ImageItem>> = _images

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    // ★★★ このinitブロックをコメントアウトまたは削除します ★★★
    /*
    init {
        fetchImagesFromUrl("https://may.2chan.net/b/futaba.php?mode=cat&sort=3")
    }
    */

    fun fetchImagesFromUrl(url: String) {
        viewModelScope.launch {
            _isLoading.value = true // 読み込み開始
            try {
                // NetworkClientがCookieを自動的に利用します
                val document = NetworkClient.fetchDocument(getApplication(), url)

                // パース処理は変更なし
                val parsedItems = mutableListOf<ImageItem>()
                val baseUrl = "https://may.2chan.net"

                val cells = document.select("#cattable td")

                for (cell in cells) {
                    val linkTag = cell.select("a").first()
                    val imgTag = cell.select("img").first()
                    val smallTag = cell.select("small").first()
                    val fontTag = cell.select("font").first()

                    if (imgTag != null && smallTag != null && fontTag != null) {
                        val relativeSrc = imgTag.attr("src")
                        val imageUrl = baseUrl + relativeSrc
                        val relativeLink = linkTag?.attr("href")
                        val detailUrl = baseUrl + "/b/" + relativeLink
                        val title = smallTag.text()
                        val replies = fontTag.text()

                        parsedItems.add(ImageItem(imageUrl, title, replies, detailUrl))
                    }
                }
                _images.value = parsedItems

            } catch (e: Exception) {
                _error.value = "データの取得に失敗しました: ${e.message}"
                e.printStackTrace()
            } finally {
                _isLoading.value = false // 読み込み終了
            }
        }
    }
}