package com.nuvio.app.features.player

// Fork-owned: upstream dropped this enum with the tabless subtitle modal
// rework, but the native overlay protocol still exchanges the active tab
// (PlayerControlsState.subtitleActiveTab).
enum class SubtitleTab {
    BuiltIn,
    Addons,
    Style,
}
