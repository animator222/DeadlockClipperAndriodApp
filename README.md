# Deadlock Clipper for Android

The Android version of [Deadlock Clipper](https://github.com/animator222/Startupideas/tree/master/DeadlockClipper):
it turns your Deadlock recordings (horizontal 1920x1080 or vertical 1080x1920) into highlight clips —
vertical 1080x1920 for YouTube Shorts and Instagram Reels, or horizontal 1920x1080 for YouTube.
Everything runs on your phone: no accounts, no uploads, no paid services.

It has the same features as the PC app: kill/assist badge detection, multikills, teamfights, death
detection, buy-menu filtering, clip lengths that follow the action, the layer-based clip editor
(gameplay, webcam/item-bar layers, text with fonts, shadow and outline), "My layout", the kill badge
area setup, YouTube downloads, the saved scans and the logs.

## Installing (about 2 minutes, once)

1. On your phone, open **https://github.com/animator222/DeadlockClipperAndriodApp/releases/tag/latest**.
2. Tap **app-release.apk** to download it.
3. Open the downloaded file. If Android asks, allow your browser/files app to **install unknown apps**,
   then tap **Install**.
4. **Deadlock Clipper** is now in your app drawer.

Needs Android 8.0 or newer. **Updating:** download and install the newest `app-release.apk` the
same way; your settings and saved scans are kept.

(The APK is built automatically by GitHub Actions on every change: the **Actions** tab has the
same file under *Artifacts*.)

## Using it

1. **Pick a video.** Tap **Choose a video file...**, or in your gallery / file manager use
   **Share → Deadlock Clipper**, or paste a YouTube link (local files are better quality and faster).
2. **Settings.** How many clips (or **All highlights**), how long, **Export as** vertical
   (Shorts/Reels) or horizontal (YouTube), and the look (tap a preview): **Full frame**,
   **Zoomed gameplay** (Zoom 110-250%) or **My layout**, and what counts as a highlight.
   Your choices are remembered.
   - **Edit my layout...** builds your own look once in the editor — e.g. zoomed gameplay, your
     webcam in a corner and your item bar at the bottom. Saved separately for each video shape and
     export shape.
   - **Set kill badge area...** if kills are missed: find a moment with a kill, drag a box around
     the badge and tap **Check this frame**. Saved separately for vertical and horizontal videos.
   - **Save clips to:** the folder inside your phone's **Movies** folder (default
     `Movies/Deadlock Clips`). Clips also show up in your gallery.
3. **Find highlights.** The whole video is checked for your kill badge using the phone's video
   hardware. A 1-2 hour video takes roughly 15-40 minutes depending on the phone; you can use other
   apps meanwhile (progress is in the notification). Keep the phone charged. Going back to the same
   video later is instant because the scan is saved.
4. **Review.** Each clip shows a thumbnail of the finished look, its time, a score and *why* it was
   picked. Tick the clips you want; **Delete** removes one; **Re-pick clips** re-chooses with a
   different fight sensitivity without re-scanning; **See what was detected** shows every moment
   that looked like your kill badge.
   - **Play / Edit** (or tap the thumbnail) opens the **editor**:
     - **Canvas** shows the finished clip, live while it plays. **Source video** shows the recording.
     - **Layers**: top of the list = in front, untick to hide. **Gameplay**: drag to move, drag a
       corner to resize, pinch to zoom. **+ Video** adds another part of the recording (webcam, item
       bar, minimap...): mark *which part* on **Source video**, then place it on **Canvas**.
       **+ Text** adds text with a font (Anton, Bebas Neue, Bangers, Luckiest Guy, Montserrat,
       Oswald, Poppins, Russo One, Roboto Black...), colour, size, bold, shadow and outline.
       ▲▼ order, ⎘ duplicate, ✕ delete. **Properties** shows the selected layer's settings.
     - Playback: play/pause, 5 s back/forward, frame by frame, mute. The **timeline** shows the clip
       as a yellow box: drag its edges to trim, or use **Set start here** / **Set end here**.
       **Play clip** plays just the trimmed part.
     - **⋮ → Apply layout to all clips** / **Save as my layout**.
     - With a keyboard attached: Space, arrows, Shift+arrows, I/O and Del work like on the PC.
5. **Export.** Clips look exactly like the editor preview and are saved as
   `deadlock_2026-09-25_clip01.mp4` and so on (the date comes from the OBS file name). Tap a clip to
   watch it, or **Share** it straight to YouTube, Instagram, WhatsApp...

## How it picks highlights

Exactly like the PC app (the same code, ported and checked against it):

| What | How it's detected |
|---|---|
| Your kill or assist | The red **KILL / ASSIST** badge above your crosshair — the current badge is found by the mint-green souls amount ("$186") inside it, older badges by their white text |
| Multikill | Two or more badges within 12 seconds |
| Teamfight | A kill badge during at least 5 seconds of loud game audio |
| Your death | The screen turning grey (death screen) |
| Creep farming | Ignored: loud, but no badge |
| Buy menu | Ignored: badges within 4 seconds of an open shop don't count |

Clip length follows the action: each clip starts when the fight starts and ends when it calms down
after your last kill; your *Clip length* setting is the allowed range.

## If something goes wrong: logs

Every error message has **Share log** and **Copy details** buttons, and the start screen has a
**Logs** button with:

| File | What it's for |
|---|---|
| `deadlock_clipper.log` | Main log. **Send this one when reporting a problem.** |
| `detections.jpg` | Picture of everything that looked like your kill badge. |
| `last_analysis.txt` | What was detected in the last video (kills, deaths, fights, chosen clips). |
| `crash.log` | Only exists if the app crashed. |

## Differences from the PC version

- Video formats: anything Android can read — MP4, MKV, MOV, WebM, 3GP, TS (H.264 works on every
  phone; HEVC/VP9/AV1 depend on the phone). FLV and AVI aren't supported by Android.
- No drag and drop: use **Choose a video file...** or **Share → Deadlock Clipper**.
- Mouse wheel zoom → pinch to zoom.
- The output folder is a folder name inside `Movies` (Android apps can't write anywhere else
  without extra permissions).
- If a phone's video encoder can't make 1080x1920 directly, the clip is encoded as 1920x1080 with a
  "rotate" flag (plays upright everywhere), or at 720p on very old phones.

## For developers

- `core/` — plain Kotlin (no Android): highlight picking, loudness analysis, settings, layouts.
  `./gradlew :core:test` checks it against output of the PC app's Python code
  (`core/src/test/resources/python_reference.json.gz`).
- `app/` — the Android app (Jetpack Compose UI). Media pipeline: `MediaExtractor`/`MediaCodec`
  decoding into an OpenGL `SurfaceTexture`; `gl/CompositionRenderer` draws the blurred background,
  video and text layers (used by the editor preview, thumbnails and export); OpenCV (`org.opencv:opencv`)
  runs the kill badge detection; `MediaCodec` H.264/AAC encoders + `MediaMuxer` write the clips;
  NewPipeExtractor handles YouTube links.
- Build: `./gradlew :app:assembleRelease` (JDK 17, Android SDK 35). CI: `.github/workflows/android.yml`.
