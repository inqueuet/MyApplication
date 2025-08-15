package com.example.myapplication

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.charset.Charset

class CommentSender(
    private val okHttpClient: OkHttpClient,
    private val okHttpCookieJar: CookieJar,
    private val contentResolver: ContentResolver,
    private val getFileNameFn: (Uri) -> String?,
    private val runOnUiThreadFn: ((() -> Unit) -> Unit),
    private val resetSubmissionStateFn: ((String?) -> Unit),
    private val showToastFn: ((String, Int) -> Unit),
    private val onSubmissionSuccessFn: ((String) -> Unit) // Renamed for clarity, takes response for logging
) {

    companion object {
        // Define keys here if they are only used by CommentSender
        // Or pass them if they are shared with DetailActivity for other purposes
        const val KEY_NAME = "name"
        const val KEY_EMAIL = "email"
        const val KEY_SUBJECT = "subject"
        const val KEY_COMMENT = "comment"
        const val KEY_SELECTED_FILE_URI = "selectedFileUri"
    }

    fun sendComment(
        commentFormDataMap: Map<String, Any?>,
        hiddenFormValues: Map<String, String>,
        currentBoardId: String?,
        maxFileSizeBytes: Long,
        targetPageUrlForFields: String?, // This is the referer
        submissionUrl: String // The actual URL to post to
    ) {
        Log.d("CommentSender", "sendComment: Starting actual submission.")

        val name = commentFormDataMap[KEY_NAME] as? String ?: ""
        val email = commentFormDataMap[KEY_EMAIL] as? String ?: ""
        val subject = commentFormDataMap[KEY_SUBJECT] as? String ?: ""
        val comment = commentFormDataMap[KEY_COMMENT] as? String ?: ""
        val currentFileUriForSubmission = commentFormDataMap[KEY_SELECTED_FILE_URI] as? Uri

        val multipartBodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("mode", "regist")
            .addFormDataPart("MAX_FILE_SIZE", maxFileSizeBytes.toString())
            .addFormDataPart("js", "on")
            .addFormDataPart("pwd", "")

        currentBoardId?.let { multipartBodyBuilder.addFormDataPart("resto", it) }
        multipartBodyBuilder.addFormDataPart("name", name)
        multipartBodyBuilder.addFormDataPart("email", email)
        multipartBodyBuilder.addFormDataPart("sub", subject)
        multipartBodyBuilder.addFormDataPart("com", comment)

        Log.d("CommentSender", "Using collected hiddenFormValues for submission: $hiddenFormValues")
        hiddenFormValues.forEach { (key, value) -> multipartBodyBuilder.addFormDataPart(key, value) }

        if (currentFileUriForSubmission == null) {
            multipartBodyBuilder.addFormDataPart("textonly", "on")
        } else {
            val resolvedFileName = getFileNameFn(currentFileUriForSubmission) ?: "attachment_${System.currentTimeMillis()}"
            val mediaTypeString = contentResolver.getType(currentFileUriForSubmission)
            val resolvedMediaType = mediaTypeString?.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()
            try {
                contentResolver.openInputStream(currentFileUriForSubmission)?.use { inputStream ->
                    val fileBytesArray = inputStream.readBytes()
                    val fileRequestBody = fileBytesArray.toRequestBody(resolvedMediaType, 0, fileBytesArray.size)
                    multipartBodyBuilder.addFormDataPart("upfile", resolvedFileName, fileRequestBody)
                    Log.d("CommentSender", "Added file to multipart: $resolvedFileName, MediaType: $resolvedMediaType, Size: ${fileBytesArray.size}")
                } ?: run {
                    Log.e("CommentSender", "Failed to open InputStream for URI: $currentFileUriForSubmission")
                    resetSubmissionStateFn("選択されたファイルを開けませんでした。")
                    return
                }
            } catch (e: IOException) {
                Log.e("CommentSender", "File reading error", e)
                resetSubmissionStateFn("ファイルの読み込み中にエラーが発生しました: ${e.message}")
                return
            } catch (e: SecurityException) {
                Log.e("CommentSender", "File permission error", e)
                resetSubmissionStateFn("ファイルへのアクセス許可がありません: ${e.message}")
                return
            }
        }

        val requestBody = multipartBodyBuilder.build()
        val submissionHttpUrl = submissionUrl.toHttpUrlOrNull()

        if (submissionHttpUrl == null) {
            Log.e("CommentSender", "Could not parse submission URL: $submissionUrl")
            resetSubmissionStateFn("内部エラー: 送信URLの解析に失敗しました。")
            return
        }

        val requestBuilder = Request.Builder()
            .url(submissionHttpUrl)
            .post(requestBody)
        targetPageUrlForFields?.let {
            requestBuilder.addHeader("Referer", it)
            Log.d("CommentSender", "Added Referer header: $it")
        }

        val cookiesForRequestHeader = okHttpCookieJar.loadForRequest(submissionHttpUrl)
        if (cookiesForRequestHeader.isNotEmpty()) {
            val builtCookieHeaderValue = cookiesForRequestHeader.joinToString(separator = "; ") { cookie ->
                "${cookie.name}=${cookie.value}"
            }
            requestBuilder.addHeader("Cookie", builtCookieHeaderValue)
            Log.d("CommentSender", "Manually added Cookie header: $builtCookieHeaderValue")
        } else {
            Log.d("CommentSender", "No cookies found in CookieJar to add to header manually.")
        }

        val finalRequest = requestBuilder.build()
        Log.d("CommentSender", "OkHttpRequest to be sent: Method=${finalRequest.method}, URL=${finalRequest.url}, Headers=${finalRequest.headers}")

        runOnUiThreadFn { showToastFn("コメントを送信中です...", 0 /* Toast.LENGTH_SHORT */) } // Using 0 for Toast.LENGTH_SHORT for simplicity
        okHttpClient.newCall(finalRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("CommentSender", "OkHttp Submission Error: ${e.message}", e)
                runOnUiThreadFn { resetSubmissionStateFn("送信に失敗しました (ネットワークエラー): ${e.message}") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { currentResponse ->
                    Log.d("CommentSender", "Response Code: ${currentResponse.code}")
                    val headersString = currentResponse.headers.toMultimap().entries.joinToString("\n") { entry -> "${entry.key}: ${entry.value.joinToString()}" }
                    Log.d("CommentSender", "Response Headers:\n$headersString")

                    val responseBytes = try { currentResponse.body?.bytes() } catch (e: Exception) { Log.e("CommentSender", "Error reading response bytes", e); null }
                    val currentResponseBodyString = responseBytes?.toString(Charset.forName("Shift_JIS")) ?: "null or empty body"
                    Log.d("CommentSender", "Response Body (Shift_JIS): $currentResponseBodyString")

                    if (currentResponse.isSuccessful) {
                        Log.i("CommentSender", "OkHttp Submission Success: Code=${currentResponse.code}")
                        if (currentResponseBodyString.contains("不正な参照元", ignoreCase = true)) {
                            runOnUiThreadFn { resetSubmissionStateFn("不正な参照元としてサーバーに拒否されました。Cookieやリクエスト内容を確認してください。") }
                            return
                        } else if (currentResponseBodyString.contains("秒、投稿できません", ignoreCase = true)) {
                            var waitTimeMessage = "連続投稿制限のため、しばらく投稿できません。"
                            try {
                                val regex = Regex("あと(\\d+)秒、投稿できません")
                                val matchResult = regex.find(currentResponseBodyString)
                                if (matchResult != null && matchResult.groupValues.size > 1) {
                                    val waitSeconds = matchResult.groupValues[1].toInt()
                                    val waitMinutes = waitSeconds / 60
                                    waitTimeMessage = if (waitMinutes > 0) {
                                        "連続投稿制限のため、あと約${waitMinutes}分お待ちください。"
                                    } else {
                                        "連続投稿制限のため、あと${waitSeconds}秒お待ちください。"
                                    }
                                }
                            } catch (e: Exception) { Log.w("CommentSender", "Failed to parse wait time from cooldown message", e) }
                            runOnUiThreadFn { resetSubmissionStateFn(waitTimeMessage) }
                            return
                        } else if (currentResponseBodyString.contains("あなたのipアドレスからは投稿できません", ignoreCase = true)) {
                            runOnUiThreadFn { resetSubmissionStateFn("あなたのIPアドレスからは投稿できません。接続環境を変更して試すか、しばらく時間をおいてください。") }
                            return
                        } else if (currentResponseBodyString.contains("cookieを有効にしてください", ignoreCase = true)) {
                            runOnUiThreadFn { resetSubmissionStateFn("Cookieが無効か、または不足しています。設定を確認し、再度お試しください。") }
                            return
                        } else if (currentResponseBodyString.contains("ERROR:", ignoreCase = true) ||
                            currentResponseBodyString.contains("エラー：", ignoreCase = true) ||
                            currentResponseBodyString.contains("<link rel=\"canonical\" href=\"https://may.2chan.net/b/futaba.htm\">", ignoreCase = true) ) {
                            var extractedErrorMessage = "サーバーが投稿を拒否しました。または予期せぬページが返されました。"
                            if (currentResponseBodyString.isNotEmpty()) {
                                if (currentResponseBodyString.contains("ERROR:") || currentResponseBodyString.contains("エラー：")) {
                                    val extracted = currentResponseBodyString.substringAfter("<h1>", "").substringBefore("</h1>", "").trim()
                                    if (extracted.isNotEmpty()) extractedErrorMessage = extracted
                                    else {
                                        val extracted2 = currentResponseBodyString.substringAfter("<font color=\"#ff0000\"><b>", "").substringBefore("</b></font>", "").trim()
                                        if(extracted2.isNotEmpty()) extractedErrorMessage = extracted2
                                    }
                                } else if (currentResponseBodyString.contains("<link rel=\"canonical\" href=\"https://may.2chan.net/b/futaba.htm\">", ignoreCase = true)) {
                                    extractedErrorMessage = "投稿が受理されず、メインページが返されました。内容を確認してください。"
                                }
                            }
                            runOnUiThreadFn { resetSubmissionStateFn(extractedErrorMessage) }
                            return
                        }
                        onSubmissionSuccessFn(currentResponseBodyString) // Pass response for potential further processing or logging
                    } else {
                        Log.w("CommentSender", "OkHttp Submission Failed: Code=${currentResponse.code}")
                        var errorMessage = "送信に失敗しました (サーバーエラーコード: ${currentResponse.code})"
                        if (currentResponseBodyString.isNotEmpty()) {
                            Log.w("CommentSender", "Failed Response Body (Shift_JIS) for Code ${currentResponse.code}:\n$currentResponseBodyString")
                            if (currentResponseBodyString.contains("不正な参照元", ignoreCase = true)) {
                                errorMessage = "不正な参照元としてサーバーに拒否されました (Code ${currentResponse.code})。"
                            } else if (currentResponseBodyString.contains("cookieを有効にしてください", ignoreCase = true)) {
                                errorMessage = "Cookieが無効のようです。設定を確認してください (Code ${currentResponse.code})。"
                            }
                        }
                        runOnUiThreadFn { resetSubmissionStateFn(errorMessage) }
                    }
                }
            }
        })
    }
}