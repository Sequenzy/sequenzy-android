package com.sequenzy.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class HttpRequest(
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String>,
    val body: String,
)

data class HttpResponse(val status: Int, val body: String)

/** Sends one HTTP request. Replaced with a fake in tests. */
fun interface SequenzyTransport {
    @Throws(IOException::class)
    suspend fun send(request: HttpRequest): HttpResponse
}

class HttpUrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000,
) : SequenzyTransport {
    override suspend fun send(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            HttpResponse(status, body)
        } finally {
            connection.disconnect()
        }
    }
}
