package com.example.hutaburakari

import android.app.Application

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PersistentCookieJar.init(applicationContext)
    }
}
