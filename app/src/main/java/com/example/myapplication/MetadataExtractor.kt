package com.example.myapplication

import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.zip.InflaterInputStream

object MetadataExtractor {

    // プロンプト情報が含まれている可能性のあるメタデータのキー
    private val PROMPT_KEYS = setOf("parameters", "Description", "Comment", "prompt")

    /**
     * URLから画像/動画データを取得し、プロンプト情報を抽出する
     */
    suspend fun extract(url: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val fileBytes = downloadFile(url) ?: return@withContext null

                // まずはExifから試す (JPEG, WEBP)
                val exifPrompt = extractFromExif(fileBytes)
                if (!exifPrompt.isNullOrBlank()) {
                    return@withContext exifPrompt
                }

                // PNGチャンクから試す
                if (isPng(fileBytes)) {
                    val pngPrompt = extractFromPngChunks(fileBytes)
                    if (!pngPrompt.isNullOrBlank()) {
                        return@withContext pngPrompt
                    }
                }

                null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * ファイルをダウンロードしてByteArrayとして返す
     */
    private fun downloadFile(fileUrl: String): ByteArray? {
        return try {
            val url = URL(fileUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.use { it.readBytes() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * EXIF情報からUserCommentを抽出する
     */
    private fun extractFromExif(fileBytes: ByteArray): String? {
        return try {
            val exifInterface = ExifInterface(ByteArrayInputStream(fileBytes))
            exifInterface.getAttribute(ExifInterface.TAG_USER_COMMENT)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * PNGのヘッダーシグネチャを持っているかチェック
     */
    private fun isPng(fileBytes: ByteArray): Boolean {
        if (fileBytes.size < 8) return false
        // PNG signature: 137 80 78 71 13 10 26 10
        // KotlinではByteとIntの直接比較ができないため、.toByte()で型を合わせる
        return fileBytes[0] == 137.toByte() &&
                fileBytes[1] == 80.toByte() &&
                fileBytes[2] == 78.toByte() &&
                fileBytes[3] == 71.toByte() &&
                fileBytes[4] == 13.toByte() &&
                fileBytes[5] == 10.toByte() &&
                fileBytes[6] == 26.toByte() &&
                fileBytes[7] == 10.toByte()
    }

    /**
     * PNGのチャンクを解析してプロンプト情報を抽出する
     */
    private fun extractFromPngChunks(bytes: ByteArray): String? {
        val prompts = mutableListOf<String>()
        var offset = 8 // PNGシグネチャの後のオフセット

        while (offset < bytes.size - 12) {
            val length = ByteBuffer.wrap(bytes, offset, 4).int
            val type = String(bytes, offset + 4, 4, StandardCharsets.US_ASCII)

            val dataStart = offset + 8
            val dataEnd = dataStart + length

            if (dataEnd > bytes.size) break

            when (type) {
                "tEXt", "iTXt", "zTXt" -> {
                    val dataBytes = bytes.sliceArray(dataStart until dataEnd)
                    val nullSeparatorIndex = dataBytes.indexOf(0.toByte())
                    if (nullSeparatorIndex > 0) {
                        val key = String(dataBytes, 0, nullSeparatorIndex, StandardCharsets.UTF_8)
                        if (PROMPT_KEYS.contains(key)) {
                            val valueBytes: ByteArray = if (type == "zTXt") {
                                // zTXtは圧縮されているため解凍処理を行う
                                // 圧縮方式のバイト(1byte)をスキップ
                                decompress(dataBytes.sliceArray(nullSeparatorIndex + 2 until dataBytes.size))
                            } else {
                                dataBytes.sliceArray(nullSeparatorIndex + 1 until dataBytes.size)
                            }
                            prompts.add(valueBytes.toString(StandardCharsets.UTF_8))
                        }
                    }
                }
                "IEND" -> {
                    // 終了チャンクなのでループを抜ける
                    return prompts.joinToString("\n\n").ifEmpty { null }
                }
            }
            offset += 12 + length // 次のチャンクへ (Length + Type + Data + CRC)
        }
        return prompts.joinToString("\n\n").ifEmpty { null }
    }

    /**
     * zlib (DEFLATE) で圧縮されたデータを解凍する
     */
    private fun decompress(compressedData: ByteArray): ByteArray {
        val inflater = InflaterInputStream(ByteArrayInputStream(compressedData))
        val outputStream = ByteArrayOutputStream()
        inflater.use { input ->
            outputStream.use { output ->
                input.copyTo(output)
            }
        }
        return outputStream.toByteArray()
    }
}