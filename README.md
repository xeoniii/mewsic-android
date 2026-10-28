> [!WARNING]
> Mewsic for android is currently under development.
>
> More info coming soon!

## mewsic-android

A Kotlin-based android music player, ported from the desktop version.

Desktop version can me found [here](https://xeoniii.github.io/Mewsic) or if you want the github repository [here](https://github.com/xeoniii/Mewsic).

# Progress

| Component | Status | Done | Not Done |
| :--- | :---: | :--- | :--- |
| **Metadata Engine** | `95%` | • Modal with Title, Artist, Album, Year, Genre<br>• Local gallery cover picker & online search<br>• In-place audio file tagging (`.mp3` ID3v2.4 & `.m4a` MP4 atom rebuilder)<br>• MediaStore and cache synchronization | • Batch tagging across multiple files |
| **Playlists System** | `90%` | • Desktop-styled "New Playlist", "Add to Playlist", & "Rename" dialogs<br>• Detail view with duration formatting (`X tracks · MM:SS`)<br>• Searchable track addition dialog<br>• Song removal & playlist deletion | • `.m3u` / `.m3u8` import & export |
| **Lyrics & Sync** | `90%` | • LRCLIB online lyrics search & fetch<br>• Real-time timestamp synchronization & auto-scroll<br>• Tap any line to seek playback<br>• Swipe-up lyrics drawer on player<br>• Sidecar `.lrc` and embedded tag reading | • Automatic background lyrics fetch for untagged tracks during playback |
| **Core Playback** | `90%` | • Fullscreen player with gestures (swipe down to close, left/right for tracks)<br>• Foreground playback service with persistent notification<br>• Lock screen playback & MediaSession controls<br>• Shuffle and Repeat (Off, All, One)<br>• UI scale manager | • Seamless / gapless playback |
| **Queue Management** | `95%` | • Explicit `playbackQueue` & index tracking<br>• "Up Next" bottom sheet with live track count & duration<br>• Drag-and-drop reordering with `ItemTouchHelper`<br>• Swipe-to-remove and single-tap deletion<br>• "Play Next" and "Add to Queue" context options<br>• One-tap "Clear" upcoming tracks | • Multi-select queue operations |
| **Harbour** | `65%` | • iTunes and JioSaavn track search engines<br>• Direct downloader with progress indicators<br>• Auto embedding of tags, artwork, & lyrics upon download | • YouTube search (InnerTube migration)<br>• SoundCloud API v2 search |
| **Audio Engine** | `40%` | • Basic preset chips | • Interactive 5-band hardware equalizer sliders<br>• BassBoost slider & virtualizer |

---

### What Is Done
- **Metadata Engine (`95%`)**:
  - Modal with Title, Artist, Album, Year, Genre.
  - Album art picker (gallery) and online artwork fetcher.
  - In-place file writing for `.m4a` (MP4 atom rebuild + chunk offsets) and `.mp3` (ID3v2.4).
  - MediaStore and in-memory cache sync.
- **Queue Management & Core Playback (`95%` / `90%`)**:
  - Explicit `playbackQueue` state management decoupled from UI tabs.
  - "Up Next" bottom sheet showing active Now Playing card and upcoming track list.
  - Interactive drag-and-drop queue reordering handle (`ItemTouchHelper`).
  - Swipe-to-dismiss queue removal + clear all upcoming tracks button.
  - "Play Next" (inserts immediately after current song) and "Add to Queue" (appends to end) contextual actions.
  - Quick queue access button on fullscreen player header (`btnFullPlayerQueue`).
  - Shuffle and Repeat modes (Off, All, One).
  - Background playback service with persistent notification and lock screen controls.
  - Swipe left/right for next/previous tracks, swipe down to dismiss player.
- **Playlists System (`90%`)**:
  - "New Playlist", "Add to Playlist", and "Rename Playlist" custom dialogs.
  - Playlist detail hero header with track count and total runtime (`MM:SS`).
  - Track addition via search selector dialog.
  - In-playlist song deletion and playlist removal.
- **Lyrics & Synchronization (`90%`)**:
  - LRCLIB search/fetch integration.
  - Real-time timestamp syncing with auto-scroll.
  - Tap-to-seek by clicking any lyric line.
  - Swipe-up gesture on the player to open synced lyrics.
  - Local sidecar `.lrc` and app-internal lyrics storage.
- **Core Playback System (`85%`)**:
  - Background playback service with persistent notification and lock screen controls.
  - MediaSession metadata and cover art broadcasting.
  - Swipe left/right for next/previous tracks, swipe down to dismiss player.
  - Shuffle and Repeat modes (Off, All, One).
  - Dynamic UI scale manager.
- **Harbour (`65%`)**:
  - iTunes and JioSaavn online track search.
  - Audio download pipeline with progress tracking.
  - Automatic tag, artwork, and lyrics embedding directly into downloaded audio files.

---

### What Is NOT Done
- **Audio Engine**:
  - 5-band hardware interactive equalizer sliders.
  - BassBoost slider and audio virtualizer.
  - Seamless/gapless playback.
- **Harbour Discovery Gaps**:
  - Direct YouTube InnerTube search engine.
  - Direct SoundCloud API v2 search.
- **Lyrics Gap**:
  - Automatic background online fetch for untagged local library tracks during playback.
- **Playlist Portability**:
  - Exporting playlists to `.m3u` / `.m3u8` files.
  - Importing `.m3u` files from external storage.
- **Batch Tagging**:
  - Multi-selection metadata editing.


