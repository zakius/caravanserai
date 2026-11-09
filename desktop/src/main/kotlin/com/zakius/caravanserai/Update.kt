package com.zakius.caravanserai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.thread

/**
 * The app's own updates. Once per start, after the first frame, the GitHub releases page says which version is the
 * latest. A newer one puts a dot on Settings, and its row opens the release page, which has the package for this
 * system. On Linux the apt or dnf repository updates the app with the system instead.
 */
object Update {
    private const val RELEASES = "https://github.com/zakius/caravanserai/releases"

    /** The newer version out, or null when this one is the latest or the check failed. */
    var available by mutableStateOf<String?>(null)
        private set
    val status: String?
        get() {
            val version = available ?: return null
            return "Opens the release page for ${BuildConfig.APP_NAME} $version"
        }

    fun init() = Startup.later {
        thread(name = "update", isDaemon = true) {
            val latest = roma.core.latestRelease(RELEASES) ?: return@thread
            if (roma.core.newerVersion(latest, BuildConfig.VERSION_NAME)) postMain { available = latest }
        }
    }

    fun install() {
        val version = available
        if (version != null) openUrl("$RELEASES/tag/v$version")
    }
}
