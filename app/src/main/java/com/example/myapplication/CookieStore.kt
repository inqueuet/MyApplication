package com.example.myapplication

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class CookieStore(context: Context) {

    private val prefs = context.getSharedPreferences("cookie_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_COOKIES = "cookies"
    }

    /**
     * SharedPreferencesに保存されているCookieに新しいCookieをマージして保存します。
     */
    fun saveCookies(cookies: Map<String, String>) {
        val existingCookies = loadCookies().toMutableMap()
        existingCookies.putAll(cookies) // 新しいCookieで既存のものを上書き・追加
        val jsonCookies = gson.toJson(existingCookies)
        prefs.edit().putString(KEY_COOKIES, jsonCookies).apply()
    }

    /**
     * SharedPreferencesからCookieを読み込みます。
     */
    fun loadCookies(): Map<String, String> {
        val jsonCookies = prefs.getString(KEY_COOKIES, null)
        return if (jsonCookies != null) {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson(jsonCookies, type)
        } else {
            emptyMap()
        }
    }
}