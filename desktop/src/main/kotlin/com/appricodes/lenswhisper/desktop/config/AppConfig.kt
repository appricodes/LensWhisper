package com.appricodes.lenswhisper.desktop.config

import java.nio.file.Path
import java.nio.file.Paths

/** Which OS family we're running on, used to pick the right llama.cpp release asset. */
enum class OsFamily { WINDOWS, LINUX, MACOS }

object AppConfig {

    /** Pinned llama.cpp release tag. Releases are nightly-tagged (e.g. "b10566"); the repo's
     * "latest" release is just a pointer file, so we don't chase it automatically — bump this
     * constant by hand when upgrading. */
    const val LLAMA_RELEASE_TAG = "b10566"

    const val DEFAULT_SERVER_PORT = 8765

    fun detectOs(): OsFamily {
        val name = System.getProperty("os.name").lowercase()
        return when {
            name.contains("win") -> OsFamily.WINDOWS
            name.contains("mac") || name.contains("darwin") -> OsFamily.MACOS
            else -> OsFamily.LINUX
        }
    }

    /** Root directory for downloaded binaries, models, and logs. Overridable with --data-dir. */
    fun dataDir(override: String? = null): Path {
        if (override != null) return Paths.get(override).toAbsolutePath()
        val home = System.getProperty("user.home")
        return when (detectOs()) {
            OsFamily.WINDOWS -> {
                val localAppData = System.getenv("LOCALAPPDATA") ?: "$home\\AppData\\Local"
                Paths.get(localAppData, "LensWhisperDesktop")
            }
            OsFamily.MACOS -> Paths.get(home, "Library", "Application Support", "LensWhisperDesktop")
            OsFamily.LINUX -> {
                val xdgData = System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
                Paths.get(xdgData, "lenswhisper-desktop")
            }
        }
    }

    fun binDir(dataDir: Path): Path = dataDir.resolve("bin").resolve(LLAMA_RELEASE_TAG)
    fun modelsDir(dataDir: Path): Path = dataDir.resolve("models")
    fun logsDir(dataDir: Path): Path = dataDir.resolve("logs")
}
