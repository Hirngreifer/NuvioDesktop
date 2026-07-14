package com.nuvio.app.features.player.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.singleWindowApplication
import com.nuvio.app.features.player.PlayerResizeMode

/**
 * Standalone measurement entry point for the Linux player surface: plays one
 * file/URL through the exact in-app render path (surface, frame store,
 * bridge) without any app navigation. Combine with NUVIO_LINUX_PERF=1 to get
 * frame timings on stderr; see scripts/linux-player-perf.sh.
 *
 * Usage: LinuxPlayerPerfHarnessKt <file-or-url> [--fullscreen]
 */
fun main(args: Array<String>) {
    val url = args.firstOrNull { !it.startsWith("--") }
        ?: error("usage: LinuxPlayerPerfHarnessKt <file-or-url> [--fullscreen]")
    val fullscreen = "--fullscreen" in args
    val state = WindowState(
        placement = if (fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating,
        size = DpSize(1280.dp, 720.dp),
    )
    // resizable=false keeps tiling WMs (Hyprland) from re-tiling the window,
    // so windowed measurements run at a reproducible surface size.
    singleWindowApplication(
        state = state,
        title = "Nuvio Linux Player Perf Harness",
        resizable = false,
    ) {
        LinuxComposePlayerSurface(
            sourceUrl = url,
            sourceHeaders = emptyMap(),
            modifier = Modifier.fillMaxSize(),
            playWhenReady = true,
            resizeMode = PlayerResizeMode.Fit,
            initialPositionMs = 0,
            onPlayerControlsEvent = { _, _ -> false },
            onControllerReady = {},
            onSnapshot = {},
            onError = { message -> System.err.println("nuvio-perf-harness error: $message") },
        )
    }
}
