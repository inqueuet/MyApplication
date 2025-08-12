package com.example.myapplication

/**
 * DetailActivityのRecyclerViewで表示するコンテンツを表すSealed Class。
 * テキストか画像かを区別するために使用します。
 */
sealed class DetailContent {
    data class Image(val imageUrl: String, val prompt: String? = null) : DetailContent()
    data class Text(val htmlContent: String) : DetailContent()
    data class Video(val videoUrl: String, val prompt: String? = null) : DetailContent()
}