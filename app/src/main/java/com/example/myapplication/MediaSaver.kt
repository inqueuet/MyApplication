package com.example.myapplication

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object MediaSaver {

    suspend fun saveImage(context: Context, imageUrl: String) {
        saveMedia(
            context = context,
            url = imageUrl,
            subfolder = "Images",
            mimeType = getMimeType(imageUrl),
            mediaContentUri = MediaStore.Images.Media.getContentUri("external_primary")
        )
    }

    suspend fun saveVideo(context: Context, videoUrl: String) {
        saveMedia(
            context = context,
            url = videoUrl,
            subfolder = "Videos",
            mimeType = getMimeType(videoUrl),
            mediaContentUri = MediaStore.Video.Media.getContentUri("external_primary")
        )
    }

    private suspend fun saveMedia(
        context: Context,
        url: String,
        subfolder: String,
        mimeType: String?,
        mediaContentUri: android.net.Uri
    ) {
        withContext(Dispatchers.IO) {
            try {
                val fileName = url.substring(url.lastIndexOf('/') + 1)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    mimeType?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/MyApplication/$subfolder")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(mediaContentUri, values)

                if (uri == null) {
                    showToast(context, "ファイルの保存に失敗しました。")
                    return@withContext
                }

                // ファイルをダウンロードして書き込む
                resolver.openOutputStream(uri).use { outputStream ->
                    if (outputStream == null) {
                        showToast(context, "ファイルの保存に失敗しました。")
                        return@withContext
                    }
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connect()
                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        connection.inputStream.use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    } else {
                        showToast(context, "ファイルのダウンロードに失敗しました。")
                        resolver.delete(uri, null, null) // 失敗した場合はエントリーを削除
                        return@withContext
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }

                showToast(context, "ファイルを保存しました: $fileName")

            } catch (e: Exception) {
                e.printStackTrace()
                showToast(context, "エラーが発生しました: ${e.message}")
            }
        }
    }

    private fun getMimeType(url: String): String? {
        val extension = MimeTypeMap.getFileExtensionFromUrl(url)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    private suspend fun showToast(context: Context, message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}