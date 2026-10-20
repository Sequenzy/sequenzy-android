package com.sequenzy.android

import android.content.Context
import com.sequenzy.core.SequenzyStore

/** Persists SDK state in a private SharedPreferences file. */
class SharedPreferencesStore(context: Context, name: String = "com.sequenzy.sdk") : SequenzyStore {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String?) {
        preferences.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }
}
