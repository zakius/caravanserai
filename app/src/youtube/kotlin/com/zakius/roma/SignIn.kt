package com.zakius.roma

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * YouTube Music sign-in in a WebView, which keeps the cookies. It avoids the Google account picker so that the app does not
 * need Play Services.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen() {
    AndroidView(factory = { context ->
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (!url.startsWith("https://music.youtube.com")) return
                    val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com") ?: return
                    if (!cookie.contains("SAPISID")) return
                    val script = "JSON.stringify([" +
                        "window.yt&&yt.config_?yt.config_.VISITOR_DATA:null, " +
                        "window.yt&&yt.config_?yt.config_.DATASYNC_ID:null])"
                    view.evaluateJavascript(script) { raw ->
                        // evaluateJavascript hands back the result as a JSON string literal, so it is decoded twice.
                        val values = runCatching {
                            val json = Store.json.decodeFromString<String>(raw)
                            Store.json.decodeFromString<List<String?>>(json)
                        }.getOrDefault(listOf(null, null))
                        signedIn(cookie, values[0], values[1])
                    }
                }
            }
            loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com")
        }
    }, modifier = Modifier.fillMaxSize())
}

/** On sign-out the sign-in page's cookies are removed too, so the next sign-in starts fresh. */
fun forgetSignIn() = CookieManager.getInstance().removeAllCookies(null)
