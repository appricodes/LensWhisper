package com.appricodes.lenswhisper.desktop.config

import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Opens the control panel in a "chromeless" app-mode window (no tabs, no address bar, its own
 * taskbar/dock entry) via a Chromium-based browser's `--app=<url>` flag, instead of a normal tab
 * in whatever the OS default browser happens to be — which, for a lot of users (this project's
 * own author included), is Firefox, which has no `--app` equivalent. Falls back to
 * [Desktop.browse] — the previous, always-working behavior — the moment no such browser is found
 * or the launch fails for any reason, so a user with neither Edge nor Chrome installed still gets
 * a working control panel, just as a normal tab like before.
 *
 * Deliberately an external-process launch, not an embedded WebView (JavaFX/SWT), to preserve the
 * accessibility this project has stuck to throughout — see the comment on `launchGui()` in
 * `Main.kt` for why a native GUI toolkit was ruled out. Edge/Chrome's own screen-reader support
 * (NVDA/JAWS/Narrator on Windows, VoiceOver on macOS, Orca where a Chromium build supports it on
 * Linux) carries over unchanged in `--app` mode; only the tab chrome is hidden, nothing about how
 * the page itself is read out changes.
 */
object BrowserLauncher {

    fun openControlPanel(url: String) {
        for (candidate in appModeCandidates()) {
            if (tryLaunchAppMode(candidate, url)) return
        }
        openInDefaultBrowser(url)
    }

    private fun appModeCandidates(): List<String> = when (AppConfig.detectOs()) {
        OsFamily.WINDOWS -> {
            val pf = System.getenv("ProgramFiles") ?: "C:\\Program Files"
            val pf86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
            val localAppData = System.getenv("LOCALAPPDATA")
            listOfNotNull(
                "$pf\\Microsoft\\Edge\\Application\\msedge.exe",
                "$pf86\\Microsoft\\Edge\\Application\\msedge.exe",
                localAppData?.let { "$it\\Microsoft\\Edge\\Application\\msedge.exe" },
                "$pf\\Google\\Chrome\\Application\\chrome.exe",
                "$pf86\\Google\\Chrome\\Application\\chrome.exe",
                localAppData?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
            )
        }
        OsFamily.MACOS -> listOf(
            "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Chromium.app/Contents/MacOS/Chromium",
        )
        // Bare commands, not absolute paths — Linux browsers are installed onto PATH, not a fixed
        // location, so these are resolved by attempting the launch itself (see tryLaunchAppMode)
        // rather than a pre-flight file-existence check.
        OsFamily.LINUX -> listOf(
            "microsoft-edge-stable", "microsoft-edge",
            "google-chrome-stable", "google-chrome",
            "chromium-browser", "chromium",
        )
    }

    private fun tryLaunchAppMode(candidate: String, url: String): Boolean {
        // Windows/macOS candidates are absolute paths — cheap to rule out up front instead of
        // paying for a failed process spawn. Linux's bare PATH commands skip straight to
        // attempting the launch (see appModeCandidates), since there's no fixed path to check.
        if (AppConfig.detectOs() != OsFamily.LINUX && !Files.isRegularFile(Paths.get(candidate))) return false
        return try {
            ProcessBuilder(candidate, "--app=$url", "--new-window")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun openInDefaultBrowser(url: String) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(URI(url))
            } catch (e: Exception) {
                println("Could not auto-open a browser (${e.message}). Open $url manually.")
            }
        } else {
            println("No graphical browser support detected. Open $url manually.")
        }
    }
}
