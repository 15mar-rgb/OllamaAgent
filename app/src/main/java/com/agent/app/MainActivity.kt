package com.agent.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val pool = Executors.newCachedThreadPool()
    private val conns = ConcurrentHashMap<String, HttpURLConnection>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0f1115"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Native")
        setContentView(web)
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl("file:///android_asset/index.html")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        conns.values.forEach { try { it.disconnect() } catch (_: Exception) {} }
        pool.shutdownNow()
        web.destroy()
        super.onDestroy()
    }

    private fun emit(id: String, type: String, data: String) {
        val js = "window.__native(${JSONObject.quote(id)},${JSONObject.quote(type)},${JSONObject.quote(data)})"
        runOnUiThread { if (!isDestroyed) web.evaluateJavascript(js, null) }
    }

    inner class Bridge {
        @JavascriptInterface
        fun request(id: String, method: String, url: String, body: String, headers: String) {
            pool.execute {
                var c: HttpURLConnection? = null
                try {
                    c = URL(url).openConnection() as HttpURLConnection
                    conns[id] = c
                    c.requestMethod = method
                    c.connectTimeout = 15000
                    c.readTimeout = 600000
                    c.instanceFollowRedirects = true
                    val h = JSONObject(headers)
                    h.keys().forEach { k -> c.setRequestProperty(k, h.getString(k)) }
                    if (body.isNotEmpty()) {
                        c.doOutput = true
                        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                    val code = c.responseCode
                    emit(id, "status", code.toString())
                    val stream = if (code >= 400) c.errorStream else c.inputStream
                    stream?.bufferedReader(Charsets.UTF_8)?.use { r ->
                        while (true) {
                            val line = r.readLine() ?: break
                            emit(id, "line", line)
                        }
                    }
                    emit(id, "end", "")
                } catch (e: Exception) {
                    emit(id, "error", e.message ?: e.toString())
                } finally {
                    conns.remove(id)
                    try { c?.disconnect() } catch (_: Exception) {}
                }
            }
        }

        @JavascriptInterface
        fun cancel(id: String) {
            conns.remove(id)?.let { c -> pool.execute { try { c.disconnect() } catch (_: Exception) {} } }
        }
    }
}
