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
        inputPwd: String?, // ユーザーが入力したパスワード
        upfileUri: Uri?,
        textOnly: Boolean,
        context: Context
    ): Result<String> {
        try {
            val baseBoardUrl = boardUrl.substringBeforeLast("futaba.php")
            if (baseBoardUrl.isEmpty() || !boardUrl.contains("futaba.php")) {
                Log.e("ReplyRepository", "Invalid boardUrl format: $boardUrl")
                return Result.failure(IllegalArgumentException("boardUrlの形式が不正です。futaba.phpを含むURLである必要があります。"))
            }
            val threadPageUrl = baseBoardUrl + "res/$resto.htm"
            Log.d("ReplyRepository", "Thread page URL for form data and Referer: $threadPageUrl")

            // 1. Fetch hash
            val hashResult = fetchHashFromThreadPage(threadPageUrl)
            if (hashResult.isFailure) {
                return Result.failure(Exception("hashの取得に失敗: ${hashResult.exceptionOrNull()?.message}"))
            }
            val hash = hashResult.getOrNull()!!

            // 2. Manage pthc and pthb
            var pthc = AppPreferences.getPthc(context)
            if (pthc == null) {
                pthc = System.currentTimeMillis().toString()
                AppPreferences.savePthc(context, pthc)
                Log.d("ReplyRepository", "New pthc generated and saved: $pthc")
            }
            val pthb = pthc // pthbは保存されたpthcと同じ値を使う

            // 3. Manage pwd
            var finalPwd = inputPwd
            if (finalPwd.isNullOrBlank()) { // ユーザーが入力しなかった場合
                var savedPwd = AppPreferences.getPwd(context)
                if (savedPwd == null) {
                    savedPwd = AppPreferences.generateNewPwd()
                    AppPreferences.savePwd(context, savedPwd)
                    Log.d("ReplyRepository", "New pwd generated and saved: $savedPwd")
                }
                finalPwd = savedPwd
            } else {
                // ユーザーが入力したpwdを保存する（任意、ここでは保存しない仕様とするが一貫性のため保存しても良い）
                 AppPreferences.savePwd(context, finalPwd) // ユーザーが入力した最新のものを保存する
            }

            val requestBodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("mode", null, "regist".toShiftJISRequestBody())
                .addFormDataPart("resto", null, resto.toShiftJISRequestBody())
                .addFormDataPart("com", null, com.toShiftJISRequestBody())

            // requestBodyBuilder.addFormDataPart("responsemode", null, "ajax".toShiftJISRequestBody())
            // requestBodyBuilder.addFormDataPart("baseform", null, "".toShiftJISRequestBody())
            // requestBodyBuilder.addFormDataPart("pthd", null, "".toShiftJISRequestBody())

            name?.takeIf { it.isNotBlank() }?.let { requestBodyBuilder.addFormDataPart("name", null, it.toShiftJISRequestBody()) }
            email?.takeIf { it.isNotBlank() }?.let { requestBodyBuilder.addFormDataPart("email", null, it.toShiftJISRequestBody()) }
            sub?.takeIf { it.isNotBlank() }?.let { requestBodyBuilder.addFormDataPart("sub", null, it.toShiftJISRequestBody()) }
            finalPwd?.takeIf { it.isNotBlank() }?.let { requestBodyBuilder.addFormDataPart("pwd", null, it.toShiftJISRequestBody()) }

            requestBodyBuilder.addFormDataPart("js", null, "on".toShiftJISRequestBody())
            requestBodyBuilder.addFormDataPart("ptua", null, "1341647872".toShiftJISRequestBody()) // 固定値

            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val scszValue = "${screenWidth}x${screenHeight}x32" // Modified to x32
            requestBodyBuilder.addFormDataPart("scsz", null, scszValue.toShiftJISRequestBody())

            requestBodyBuilder.addFormDataPart("chrenc", null, "文字".toShiftJISRequestBody())

            requestBodyBuilder.addFormDataPart("pthc", null, pthc.toShiftJISRequestBody())
            requestBodyBuilder.addFormDataPart("pthb", null, pthb.toShiftJISRequestBody())
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

            val request = Request.Builder()
                .url(boardUrl)
                .header("Referer", threadPageUrl)
                .header("Origin", baseBoardUrl.removeSuffix("/"))
                .post(finalRequestBody)
                .build()

            Log.d("ReplyRepository", "Posting to: $boardUrl with Referer: $threadPageUrl and Origin: ${baseBoardUrl.removeSuffix("/")}")

            httpClient.newCall(request).execute().use { response ->
                val responseBodyString = response.body?.source()?.readString(Charset.forName("Shift_JIS")) ?: ""
                Log.d("ReplyRepository", "Response code: ${response.code}")
                Log.d("ReplyRepository", "Response body: $responseBodyString")

                if (!response.isSuccessful) {
                    return Result.failure(IOException("投稿失敗 (HTTP ${response.code}): ${response.message}\n${responseBodyString.take(500)}"))
                }

                try {
                    val futabaResponse = gson.fromJson(responseBodyString, FutabaResponse::class.java)
                    if ("ok".equals(futabaResponse.status, ignoreCase = true)) {
                        return Result.success("投稿成功！ レスNo: ${futabaResponse.thisno ?: "不明"}")
                    } else {
                        Log.w("ReplyRepository", "JSON response status was not 'ok': ${futabaResponse.status}")
                        return Result.failure(IOException("投稿失敗 (JSONステータス: ${futabaResponse.status ?: "不明"})"))
                    }
                } catch (e: JsonSyntaxException) {
                    Log.w("ReplyRepository", "Response was not valid JSON, attempting HTML error parsing. Body: $responseBodyString", e)
                    if (responseBodyString.contains("ＥＲＲＯＲ！") ||
                        responseBodyString.contains("連続投稿です") ||
                        responseBodyString.contains("本文がありません") ||
                        responseBodyString.contains("不正な参照元")) {
                        val doc = Jsoup.parse(responseBodyString)
                        val errorMessage = doc.select("font[color='red'] b").firstOrNull()?.text()
                            ?: doc.select("h1").firstOrNull()?.text()
                            ?: doc.select("p.main").firstOrNull()?.text()
                            ?: responseBodyString.take(100)
                            ?: "投稿エラーが検出されました (HTMLフォールバック)"
                        return Result.failure(IOException("投稿エラー (HTML): $errorMessage"))
                    }
                    return Result.failure(IOException("不明なレスポンス形式: ${responseBodyString.take(500)}"))
                } catch (e: Exception) {
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
        return this.toByteArray(Charset.forName("Shift_JIS"))
            .toRequestBody("text/plain; charset=Shift_JIS".toMediaTypeOrNull())
    }

    // Fetches only the 'hash' value from the thread page.
    private suspend fun fetchHashFromThreadPage(threadUrl: String): Result<String> {
        try {
            val request = Request.Builder().url(threadUrl).get().build()
            Log.d("ReplyRepository", "Fetching hash from: $threadUrl")
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("ReplyRepository", "Failed to fetch page for hash: ${response.code} ${response.message} from $threadUrl")
                    return Result.failure(IOException("フォームページ取得失敗(${response.code})"))
                }
                val htmlBody = response.body?.source()?.readString(Charset.forName("Shift_JIS"))
                if (htmlBody == null) {
                    Log.w("ReplyRepository", "Form page HTML body is null (for hash) from $threadUrl")
                    return Result.failure(IOException("フォームページのHTML取得失敗"))
                }
                val document = Jsoup.parse(htmlBody)
                val hashValue = document.select("input[name=hash]").first()?.attr("value")

                if (hashValue.isNullOrEmpty()) {
                    Log.w("ReplyRepository", "Hash value not found in form: $threadUrl. HTML snippet: ${htmlBody.substring(0, htmlBody.length.coerceAtMost(1000))}")
                    return Result.failure(IOException("'hash'が見つかりません。ページを確認してください。"))
                }
                Log.d("ReplyRepository", "Fetched hash: $hashValue")
                return Result.success(hashValue)
            }
        } catch (e: Exception) {
            Log.e("ReplyRepository", "Failed to fetch hash from $threadUrl", e)
            return Result.failure(e)
        }
    }
}
