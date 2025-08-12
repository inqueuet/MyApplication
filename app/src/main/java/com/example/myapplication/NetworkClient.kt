package com.example.myapplication

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

object NetworkClient {

    suspend fun fetchDocument(context: Context, url: String): Document {
        // IOスレッドでネットワーク処理を実行
        return withContext(Dispatchers.IO) {
            val cookieStore = CookieStore(context.applicationContext)
            val storedCookies = cookieStore.loadCookies()

            // 保存されたCookieを付けてリクエストを実行
            val response = Jsoup.connect(url)
                .cookies(storedCookies)
                .method(Connection.Method.GET)
                .execute()

            // レスポンスから新しいCookieを取得して保存
            val newCookies = response.cookies()
            if (newCookies.isNotEmpty()) {
                cookieStore.saveCookies(newCookies)
            }

            // HTMLドキュメントをパースして返す
            response.parse()
        }
    }

    // ★★★ ここから新しい関数を追加 ★★★
    /**
     * 設定値をサーバーにPOST送信して、返却されたCookieを保存する
     */
    suspend fun applySettings(context: Context, settings: Map<String, String>) {
        withContext(Dispatchers.IO) {
            val settingsUrl = "https://may.2chan.net/b/futaba.php?mode=catset"
            val cookieStore = CookieStore(context.applicationContext)
            val storedCookies = cookieStore.loadCookies()

            // 現在のCookieを付けて、設定値をPOSTで送信
            val response = Jsoup.connect(settingsUrl)
                .cookies(storedCookies)
                .data(settings) // 設定値をフォームデータとして追加
                .method(Connection.Method.POST)
                .execute()

            // サーバーから返された新しいCookieを保存
            val newCookies = response.cookies()
            if (newCookies.isNotEmpty()) {
                cookieStore.saveCookies(newCookies)
            }
        }
    }
    // ★★★ ここまで追加 ★★★
}