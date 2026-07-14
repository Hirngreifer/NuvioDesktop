package com.nuvio.app.core.logging

import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

/**
 * Mirrors stdout/stderr (and thereby Kermit's console writer and every
 * uncaught-exception stack trace) into a per-run log file, so failures on
 * end-user machines — where the jpackage GUI launcher has no console — can
 * be diagnosed after the fact.
 *
 * Log location: `<app-data>/Nuvio/logs/nuvio-last-run.log`, truncated on
 * every start (the previous run is kept as `nuvio-previous-run.log`).
 * Windows: %APPDATA%\Nuvio\logs — same base dir as DesktopStorage.
 */
internal object DesktopFileLogging {

    fun install() {
        runCatching {
            val dir = resolveLogsDir()
            Files.createDirectories(dir)
            val current = dir.resolve("nuvio-last-run.log")
            val previous = dir.resolve("nuvio-previous-run.log")
            runCatching {
                if (Files.exists(current)) {
                    Files.deleteIfExists(previous)
                    Files.move(current, previous)
                }
            }
            val fileStream = FileOutputStream(current.toFile(), false)
            System.setOut(PrintStream(TeeOutputStream(System.out, fileStream), true))
            System.setErr(PrintStream(TeeOutputStream(System.err, fileStream), true))

            val delegate = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                System.err.println("FATAL: uncaught exception on thread ${thread.name}")
                throwable.printStackTrace(System.err)
                delegate?.uncaughtException(thread, throwable)
            }
            System.out.println("nuvio-desktop: logging to $current")
        }
    }

    private fun resolveLogsDir(): Path {
        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val userHome = Paths.get(System.getProperty("user.home").orEmpty())
        val base = when {
            osName.contains("mac") -> userHome.resolve("Library/Application Support/Nuvio")
            osName.contains("win") -> {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                (appData?.let(Paths::get) ?: userHome.resolve("AppData/Roaming")).resolve("Nuvio")
            }
            else -> {
                val xdgConfig = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                (xdgConfig?.let(Paths::get) ?: userHome.resolve(".config")).resolve("nuvio")
            }
        }
        return base.resolve("logs")
    }

    /** Writes to both streams; failures on the file side never break the console side. */
    private class TeeOutputStream(
        private val primary: OutputStream,
        private val secondary: OutputStream,
    ) : OutputStream() {
        override fun write(b: Int) {
            primary.write(b)
            runCatching { secondary.write(b) }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            primary.write(b, off, len)
            runCatching { secondary.write(b, off, len) }
        }

        override fun flush() {
            primary.flush()
            runCatching { secondary.flush() }
        }
    }
}
