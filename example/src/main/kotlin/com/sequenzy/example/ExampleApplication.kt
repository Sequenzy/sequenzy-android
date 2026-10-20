package com.sequenzy.example

import android.app.Application
import com.sequenzy.android.Sequenzy
import com.sequenzy.core.LogLevel

class ExampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Sequenzy.configure(
            application = this,
            publicKey = BuildConfig.SEQUENZY_PUBLIC_KEY,
            companyId = BuildConfig.SEQUENZY_COMPANY_ID,
            apiBaseUrl = BuildConfig.SEQUENZY_API_BASE_URL,
            logLevel = LogLevel.DEBUG,
        )
    }
}
