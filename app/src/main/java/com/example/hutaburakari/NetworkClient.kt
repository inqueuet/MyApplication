package com.example.hutaburakari

import android.content.Context
import android.util.Log // ★ Logをインポート
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException

object NetworkClient {

    suspend fun fetchDocument(context: Context, url: String): Document {
        return withContext(Dispatchers.IO) {
            val cookieStore = CookieStore(context.applicationContext)
            val storedCookies = cookieStore.loadCookies()
            val response = Jsoup.connect(url)
                .cookies(storedCookies)
                .method(Connection.Method.GET)
                .execute()
            val newCookies = response.cookies()
            if (newCookies.isNotEmpty()) {
                cookieStore.saveCookies(newCookies)
            }
            response.parse()
        }
    }

    suspend fun postSodaNe(context: Context, resNum: String, referer: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://may.2chan.net/sd.php?b.$resNum"
                Log.d("NetworkClient", "postSodaNe: Attempting to GET to URL: $url with referer: $referer") // Changed POST to GET in log
                val cookieStore = CookieStore(context.applicationContext)
                val storedCookies = cookieStore.loadCookies()
                Log.d("NetworkClient", "postSodaNe: Cookies being sent: $storedCookies")

                val response = Jsoup.connect(url)
                    .cookies(storedCookies)
                    .header("accept", "*/*")
                    .header("accept-encoding", "gzip, deflate, br, zstd")
                    .header("accept-language", "ja,en-US;q=0.9,en;q=0.8")
                    .header("connection", "keep-alive")
                    .header("host", "may.2chan.net")
                    .header("referer", referer)
                    .method(Connection.Method.GET) // Changed to GET
                    .ignoreContentType(true) 
                    .execute()
                
                Log.d("NetworkClient", "postSodaNe: Response status code: ${response.statusCode()}")
                Log.d("NetworkClient", "postSodaNe: Response body: ${response.body()}")
                
                // ステータスコードが2xx範囲内であれば成功とみなす
                if (response.statusCode() in 200..299) {
                     true
                } else {
                    Log.w("NetworkClient", "postSodaNe: Failed with status ${response.statusCode()} and body: ${response.body()}")
                    false
                }
            } catch (e: IOException) {
                Log.e("NetworkClient", "postSodaNe: IOException: ${e.message}", e)
                e.printStackTrace()
                false
            } catch (e: Exception) {
                Log.e("NetworkClient", "postSodaNe: General Exception: ${e.message}", e)
                e.printStackTrace()
                false
            }
        }
    }

    suspend fun applySettings(context: Context, settings: Map<String, String>) {
        withContext(Dispatchers.IO) {
            val settingsUrl = "https://may.2chan.net/b/futaba.php?mode=catset"
            val cookieStore = CookieStore(context.applicationContext)
            val storedCookies = cookieStore.loadCookies()
            val response = Jsoup.connect(settingsUrl)
                .cookies(storedCookies)
                .data(settings)
                .method(Connection.Method.POST)
                .execute()
            val newCookies = response.cookies()
            if (newCookies.isNotEmpty()) {
                cookieStore.saveCookies(newCookies)
            }
        }
    }
}
