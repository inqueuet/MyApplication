package com.example.hutaburakari

import android.app.Application
import dagger.hilt.android.HiltAndroidApp // 追加

@HiltAndroidApp // 追加
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // PersistentCookieJar.init(applicationContext) // 削除
    }
}
