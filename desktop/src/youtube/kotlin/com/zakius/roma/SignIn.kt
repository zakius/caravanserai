package com.zakius.roma

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The sign-in page. The sign-in itself happens in a browser window (Browser.kt), and this page waits for it. */
@Composable
fun LoginScreen() {
    LaunchedEffect(Unit) {
        // After a sign-in, signedIn() has already navigated away from this page. When the window is closed without a
        // sign-in, the page removes itself here.
        if (!browserSignIn()) Nav.stacks[Nav.tab].removeLastOrNull()
    }
    Column(Modifier.padding(16.dp)) {
        Text("Sign in to YouTube Music in the browser window roma opened, then close the window.")
        val status = Cef.status
        if (status != null) Text(status, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Deletes the sign-in browser profiles and their cookies on sign-out, so the next sign-in starts fresh. There are two:
 * the system browser's, and CEF's, used when the system has no suitable browser.
 */
fun forgetSignIn() {
    Browser.profileDir("signin").deleteRecursively()
    Browser.profileDir("cef").deleteRecursively()
}
