package com.zakius.roma

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The sign-in page: the sign-in itself is in a browser window (Browser.kt); this page waits for it. */
@Composable
fun LoginScreen() {
    LaunchedEffect(Unit) {
        // Signed in, signedIn() has left this page; a closed window leaves it here.
        if (!browserSignIn()) Nav.stacks[Nav.tab].removeLastOrNull()
    }
    Column(Modifier.padding(16.dp)) {
        Text("Sign in to YouTube Music in the browser window roma opened, then close the window.")
        Cef.status?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * Signing out: the sign-in profiles' cookies go too (a system browser's, and CEF's, which a sign-in without one used),
 * so the next sign-in starts fresh.
 */
fun forgetSignIn() {
    Browser.profileDir("signin").deleteRecursively()
    Browser.profileDir("cef").deleteRecursively()
}
