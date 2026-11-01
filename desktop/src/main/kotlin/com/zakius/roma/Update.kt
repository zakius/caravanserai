package com.zakius.roma

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.thread

/**
 * roma's own updates: once per start, after the first frame, the GitHub releases page says which version is the latest.
 * A newer one puts a dot on Settings; its row opens the release page, where the package for this system is. On Linux
 * the apt or dnf repository updates roma with the system instead.
 */
object Update {
    private const val RELEASES = "https://github.com/zakius/roma/releases"

    /** The newer version out, null when this one is the latest or the check failed. */
    var available by mutableStateOf<String?>(null)
        private set
    val status: String? get() = available?.let { "Opens the release page for ${BuildConfig.APP_NAME} $it" }

    fun init() = Startup.later {
        thread(name = "update", isDaemon = true) {
            val latest = roma.core.latestRelease(RELEASES) ?: return@thread
            if (roma.core.newerVersion(latest, BuildConfig.VERSION_NAME)) postMain { available = latest }
        }
    }

    fun install() { available?.let { openUrl("$RELEASES/tag/v$it") } }
}
