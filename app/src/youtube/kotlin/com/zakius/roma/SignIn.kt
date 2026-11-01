package com.zakius.roma

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** YouTube Music sign-in in a WebView; the cookies are kept. No Google account picker, so no Play Services. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen() {
    AndroidView(factory = { c ->
        WebView(c).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (!url.startsWith("https://music.youtube.com")) return
                    val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com") ?: return
                    if (!cookie.contains("SAPISID")) return
                    view.evaluateJavascript("JSON.stringify([window.yt&&yt.config_?yt.config_.VISITOR_DATA:null, window.yt&&yt.config_?yt.config_.DATASYNC_ID:null])") { raw ->
                        val values = runCatching { Store.json.decodeFromString<List<String?>>(Store.json.decodeFromString<String>(raw)) }.getOrDefault(listOf(null, null))
                        signedIn(cookie, values[0], values[1])
                    }
                }
            }
            loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com")
        }
    }, modifier = Modifier.fillMaxSize())
}

/** Signing out: the sign-in page's cookies go too, so the next sign-in starts fresh. */
fun forgetSignIn() = CookieManager.getInstance().removeAllCookies(null)
