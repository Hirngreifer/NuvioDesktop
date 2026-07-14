# Linux player: render path & performance

The Linux player has no native child window (Wayland offers no equivalent of
the Windows/macOS embedding), so every video frame travels through the UI:

```
file → decoder (hwdec) → mpv GL render (offscreen EGL/FBO)
     → async PBO readback (fenced, never blocking)
     → Skia bitmap storage (bridge writes into it directly, zero copies)
     → Compose canvas draws the bitmap snapshot
```

Key properties of the pipeline (`native/linux/player_bridge.c`,
`LinuxComposePlayerSurface.kt`):

- **Readback** goes through two `GL_PIXEL_PACK_BUFFER`s with fence syncs.
  A frame is submitted as an asynchronous pack transfer and delivered on a
  later call once its fence signals; delivery never blocks on the GPU queue,
  and a still-pending PBO is never overwritten (frames drop instead —
  measured: a blocking readback cost 50–230 ms per 720p frame on Iris Xe,
  the pipelined path ~0.3 ms).
- **Pixel format** is RGBA end to end (`GL_RGBA` readback from the
  `GL_RGBA8` FBO, `rgb0` in the software renderer, `RGBA_8888` in Skia).
  Reading back as BGRA knocks Mesa off its blit fastpath into a per-pixel
  CPU conversion (140–230 ms per 720p frame).
- **Buffers track the canvas size, capped at the video's native size**
  (`renderBufferSize`): a 1080p video fullscreen on a 4K canvas moves 8 MB
  per frame instead of 33 MB.
- **The bridge renders into the Skia bitmaps' own pixel storage** (raw
  address); publishing a frame is an index swap under a lock, the UI
  snapshots under the same lock. No per-frame copies on the Kotlin side.
- **hwdec:** the GL path passes a DRM render node to the mpv render context
  so VAAPI/CUDA interop works without any window system, and uses native
  `hwdec=auto` (copy modes cap 4K60 decode at ~17 fps on Iris Xe). Because
  some driver stacks render imported hwdec surfaces pathologically slowly,
  a watchdog times the first renders and downgrades to `auto-copy` once if
  they average above 25 ms/frame.

## Environment variables

| Variable | Effect |
|---|---|
| `NUVIO_LINUX_RENDER=sw` | Force the software render backend (no GL) |
| `NUVIO_MPV_HWDEC=<mode>` | Override hwdec (`auto`, `auto-copy`, `no`, …); disables the watchdog |
| `NUVIO_LINUX_PERF=1` | Frame-timing summaries on stderr (Kotlin loop + C render/readback split) |
| `NUVIO_MPV_VERBOSE=1` | Verbose mpv log |
| `NUVIO_MPV_AO=null` | Audio output override, for isolating A-V-sync issues |

## Measuring

`scripts/linux-player-perf.sh <file-or-url> [--fullscreen]` runs the exact
in-app render path (surface, frame store, bridge) in a standalone window
with `NUVIO_LINUX_PERF=1`. Test clips: Big Buck Bunny (`bbb_sunflower`)
1080p60/2160p60 cuts.

Reference numbers, 1080p60 clip, 1280×720 window, Iris Xe (RPL-P), 2026-07:

| | before (blocking BGRA readback, hwdec=auto-copy) | after |
|---|---|---|
| fps | 13–35, degrading | stable 60 |
| render() per frame | 25–61 ms | 0.35 ms |
| Kotlin copy per frame | ~3 ms | 0 ms |
| JVM CPU (of one core) | 87 % | 39 % |

## Known machine-level limits (not fixable in the app)

On the reference machine (Mesa iHD/i915, kernel 7.0.x, 2026-07) two driver
pathologies exist that also affect standalone mpv:

- `vaapi-copy` decode of 4K tops out at ~17 fps (`vaGetImage` over uncached
  memory), so 4K60 cannot reach full rate in copy mode.
- Rendering imported native-VAAPI 4K surfaces through GL interop stalls
  150–800 ms per frame (any VA display type; 1080p is fine). The watchdog
  catches this and falls back to `auto-copy`.

Net effect there: 1080p60 is perfect, 4K is capped at ~17 fps by the driver
stack. On healthy stacks native interop keeps 4K frames on the GPU end to
end and only the readback (8–33 MB per frame, asynchronous) remains.
