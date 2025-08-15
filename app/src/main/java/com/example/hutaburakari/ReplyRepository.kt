package com.example.hutaburakari

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.io.IOException
import java.nio.charset.Charset

// データクラスをファイルレベルに定義 (または適切な場所に移動)
data class FutabaResponse(
    val status: String?,
    val jumpto: Long?,
    val resto: Long?,
    val thisno: Long?,
    val bbscode: String?,
    // ふたばのエラーJSONは単純なHTMLを返すことが多いので、専用のエラーフィールドは期待しにくい
    // HTMLエラーメッセージは別途Jsoupでパースする可能性がある
)

class ReplyRepository(
    private val httpClient: OkHttpClient,
    private val gson: Gson = Gson() // Gsonインスタンスを持たせる
) {

    suspend fun postReply(
        boardUrl: String, // futaba.phpのURL (例: https://may.2chan.net/b/futaba.php?guid=on)
        resto: String,    // スレッドID
        name: String?,
        email: String?,
        sub: String?,
        com: String,
        pwd: String?,
        upfileUri: Uri?,
        textOnly: Boolean,
        context: Context
    ): Result<String> {
        try {
            // スレッドページのURL (Refererやフォームデータ取得元として使用)
            // boardUrlが "https://server/board/futaba.php?guid=on" のような形式を想定
            val baseBoardUrl = boardUrl.substringBeforeLast("futaba.php")
            if (baseBoardUrl.isEmpty() || !boardUrl.contains("futaba.php")) {
                Log.e("ReplyRepository", "Invalid boardUrl format: $boardUrl")
                return Result.failure(IllegalArgumentException("boardUrlの形式が不正です。futaba.phpを含むURLである必要があります。"))
            }
            val threadPageUrl = baseBoardUrl + "res/$resto.htm"
            Log.d("ReplyRepository", "Thread page URL for form data and Referer: $threadPageUrl")

            val formDataResult = fetchRequiredFormData(threadPageUrl)
            if (formDataResult.isFailure) {
                return Result.failure(Exception("フォームデータの取得に失敗: ${formDataResult.exceptionOrNull()?.message}"))
            }
            var (hash, pthcValue, pthbValue) = formDataResult.getOrNull()!!

            val currentTimeMillisString = System.currentTimeMillis().toString()
            if (pthcValue.isNullOrEmpty()) {
                pthcValue = currentTimeMillisString
                Log.d("ReplyRepository", "pthcValue was empty, setting to current time: $pthcValue")
            }
            if (pthbValue.isNullOrEmpty()) {
                pthbValue = currentTimeMillisString // ブラウザの成功例ではpthcと同一だったため
                Log.d("ReplyRepository", "pthbValue was empty, setting to current time (same as pthc): $pthbValue")
            }

            val requestBodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("mode", null, "regist".toShiftJISRequestBody())
                .addFormDataPart("resto", null, resto.toShiftJISRequestBody())
                .addFormDataPart("com", null, com.toShiftJISRequestBody())

            // ブラウザの成功リクエストに基づき追加
            requestBodyBuilder.addFormDataPart("responsemode", null, "ajax".toShiftJISRequestBody())
            requestBodyBuilder.addFormDataPart("baseform", null, "".toShiftJISRequestBody()) // 空の値
            requestBodyBuilder.addFormDataPart("pthd", null, "".toShiftJISRequestBody())     // 空の値

            name?.takeIf { it.isNotBlank() }?.let { requestBodyBuilder.addFormDataPart("name", null, it.toShiftJISRequestBody()) }
            email?.takeIf { it.isNotBlank() }?.let { requestBodyBuilder.addFormDataPart("email", null, it.toShiftJISRequestBody()) }
            sub?.takeIf { it.isNotBlank() }?.let { requestBodyBuilder.addFormDataPart("sub", null, it.toShiftJISRequestBody()) }
            pwd?.takeIf { it.isNotBlank() }?.let { requestBodyBuilder.addFormDataPart("pwd", null, it.toShiftJISRequestBody()) }

            requestBodyBuilder.addFormDataPart("js", null, "on".toShiftJISRequestBody())
            // ptua はブラウザの値を参考に固定値にするか、より動的な生成方法を検討 (現状維持)
            requestBodyBuilder.addFormDataPart("ptua", null, "1341647872".toShiftJISRequestBody()) // ブラウザの値を仮使用

            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            // scsz もブラウザの値を参考 (色深度24bit)
            val scszValue = "${screenWidth}x${screenHeight}x24" // ブラウザに合わせる
            requestBodyBuilder.addFormDataPart("scsz", null, scszValue.toShiftJISRequestBody())

            requestBodyBuilder.addFormDataPart("chrenc", null, "文字".toShiftJISRequestBody())

            pthcValue?.let { requestBodyBuilder.addFormDataPart("pthc", null, it.toShiftJISRequestBody()) }
            pthbValue?.let { requestBodyBuilder.addFormDataPart("pthb", null, it.toShiftJISRequestBody()) }
            requestBodyBuilder.addFormDataPart("hash", null, hash.toShiftJISRequestBody())


            if (textOnly || upfileUri == null) {
                requestBodyBuilder.addFormDataPart("textonly", null, "on".toShiftJISRequestBody())
            } else {
                val fileName = getFileNameFromUri(context, upfileUri) ?: "upload_file"
                val mimeType = context.contentResolver.getType(upfileUri) ?: "application/octet-stream"

                context.contentResolver.openInputStream(upfileUri)?.use { inputStream ->
                    val fileRequestBody = inputStream.readBytes().toRequestBody(mimeType.toMediaTypeOrNull())
                    requestBodyBuilder.addFormDataPart("upfile", fileName, fileRequestBody)
                } ?: return Result.failure(IOException("ファイルストリームの取得に失敗: $upfileUri"))

                requestBodyBuilder.addFormDataPart("MAX_FILE_SIZE", null, (8 * 1024 * 1024).toString().toShiftJISRequestBody())
            }

            val finalRequestBody = requestBodyBuilder.build()

            // リクエストボディのデバッグログ (Shift_JISのままだと文字化けするので注意)
            // val buffer = okio.Buffer()
            // finalRequestBody.writeTo(buffer)
            // Log.d("ReplyRepository", "Request Body (raw bytes might be Shift_JIS): ${buffer.readByteString().hex()}")


            val request = Request.Builder()
                .url(boardUrl)
                .header("Referer", threadPageUrl)
                .header("Origin", baseBoardUrl.removeSuffix("/")) // 例: https://may.2chan.net (末尾スラッシュなし)
                .post(finalRequestBody)
                .build()

            Log.d("ReplyRepository", "Posting to: $boardUrl with Referer: $threadPageUrl and Origin: ${baseBoardUrl.removeSuffix("/")}")

            httpClient.newCall(request).execute().use { response ->
                val responseBodyString = response.body?.source()?.readString(Charset.forName("Shift_JIS")) ?: ""
                Log.d("ReplyRepository", "Response code: ${response.code}")
                Log.d("ReplyRepository", "Response body: $responseBodyString") // 全体ログ

                if (!response.isSuccessful) {
                    return Result.failure(IOException("投稿失敗 (HTTP ${response.code}): ${response.message}\n${responseBodyString.take(500)}"))
                }

                try {
                    val futabaResponse = gson.fromJson(responseBodyString, FutabaResponse::class.java)
                    if ("ok".equals(futabaResponse.status, ignoreCase = true)) {
                        return Result.success("投稿成功！ レスNo: ${futabaResponse.thisno ?: "不明"}")
                    } else {
                        // JSONレスポンスだが status が "ok" でない場合
                        // futabaの仕様では、エラー時はHTMLを返すことが多いので、ここに来るケースは稀かもしれない
                        Log.w("ReplyRepository", "JSON response status was not 'ok': ${futabaResponse.status}")
                        return Result.failure(IOException("投稿失敗 (JSONステータス: ${futabaResponse.status ?: "不明"})"))
                    }
                } catch (e: JsonSyntaxException) {
                    // JSONパースに失敗した場合、HTMLエラーページと仮定
                    Log.w("ReplyRepository", "Response was not valid JSON, attempting HTML error parsing. Body: $responseBodyString", e)
                    if (responseBodyString.contains("ＥＲＲＯＲ！") ||
                        responseBodyString.contains("連続投稿です") ||
                        responseBodyString.contains("本文がありません") ||
                        // ... (他のHTMLエラーメッセージ)
                        responseBodyString.contains("不正な参照元")) { // `不正な参照元です。` ではなく `不正な参照元`
                        val doc = Jsoup.parse(responseBodyString)
                        val errorMessage = doc.select("font[color='red'] b").firstOrNull()?.text()
                            ?: doc.select("h1").firstOrNull()?.text()
                            ?: doc.select("p.main").firstOrNull()?.text()
                            ?: responseBodyString.take(100) // エラー箇所が特定できない場合はボディの先頭
                            ?: "投稿エラーが検出されました (HTMLフォールバック)"
                        return Result.failure(IOException("投稿エラー (HTML): $errorMessage"))
                    }
                    // 知らないHTMLエラーまたは全く異なるレスポンス
                    return Result.failure(IOException("不明なレスポンス形式: ${responseBodyString.take(500)}"))
                } catch (e: Exception) {
                    // その他の予期せぬ例外
                    Log.e("ReplyRepository", "Error processing response", e)
                    return Result.failure(IOException("レスポンス処理エラー: ${e.message}"))
                }
            }
        } catch (e: Exception) {
            Log.e("ReplyRepository", "Post failed", e)
            return Result.failure(e)
        }
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        // (変更なし)
        var fileName: String? = null
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex != -1) {
                        fileName = cursor.getString(displayNameIndex)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e("ReplyRepository", "SecurityException getting filename from URI: $uri", e)
        }
        if (fileName == null) {
            fileName = uri.path?.substringAfterLast('/')
        }
        return fileName
    }

    private fun String.toShiftJISRequestBody(): RequestBody {
        // (変更なし)
        return this.toByteArray(Charset.forName("Shift_JIS"))
            .toRequestBody("text/plain; charset=Shift_JIS".toMediaTypeOrNull())
    }

    private suspend fun fetchRequiredFormData(threadUrl: String): Result<Triple<String, String?, String?>> {
        // (変更なし、ただしpthc/pthbが空で返ってくる問題は別途調査・対応が必要な場合あり)
        try {
            val request = Request.Builder().url(threadUrl).get().build()
            Log.d("ReplyRepository", "Fetching form data from: $threadUrl")
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("ReplyRepository", "Failed to fetch form page: ${response.code} ${response.message} from $threadUrl")
                    return Result.failure(IOException("フォームページ取得失敗(${response.code})"))
                }
                val htmlBody = response.body?.source()?.readString(Charset.forName("Shift_JIS"))
                if (htmlBody == null) {
                    Log.w("ReplyRepository", "Form page HTML body is null from $threadUrl")
                    return Result.failure(IOException("フォームページのHTML取得失敗"))
                }
                val document = Jsoup.parse(htmlBody)
                val hashValue = document.select("input[name=hash]").first()?.attr("value")
                val pthcValue = document.select("input[name=pthc]").first()?.attr("value")
                val pthbValue = document.select("input[name=pthb]").first()?.attr("value")

                if (hashValue.isNullOrEmpty()) {
                    Log.w("ReplyRepository", "Hash value not found in form: $threadUrl. HTML snippet: ${htmlBody.substring(0, htmlBody.length.coerceAtMost(1000))}")
                    return Result.failure(IOException("'hash'が見つかりません。ページを確認してください。"))
                }
                Log.d("ReplyRepository", "Fetched form data: hash=$hashValue, pthc=$pthcValue, pthb=$pthbValue")
                return Result.success(Triple(hashValue, pthcValue, pthbValue))
            }
        } catch (e: Exception) {
            Log.e("ReplyRepository", "Failed to fetch form data from $threadUrl", e)
            return Result.failure(e)
        }
    }
}