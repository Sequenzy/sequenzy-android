package com.sequenzy.core

import java.util.concurrent.ConcurrentHashMap

/** Persistent string storage for identity, the event queue and push state. */
interface SequenzyStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

class InMemoryStore(initial: Map<String, String> = emptyMap()) : SequenzyStore {
    private val values = ConcurrentHashMap(initial)
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}
