# FlashbackNH

Record your gameplay, play it back and create cinematic shots in **GT New Horizons** (Minecraft 1.7.10, Forge).

FlashbackNH is an **unofficial, independent reimplementation** of the feature set of
[Flashback](https://github.com/Moulberry/Flashback) (a replay mod for modern Fabric versions) for the 1.7.10 / GTNH
ecosystem. It is written from scratch and contains **no code from Flashback**: Flashback's source code is
"all rights reserved", so it cannot be ported or redistributed. FlashbackNH is not affiliated with or endorsed by
Flashback or its author. Please do not report FlashbackNH issues to the Flashback project.

FlashbackNH is client-side only. It works in singleplayer and on servers; the server does not need it.

## Features

### Recording
- Start, pause, unpause, finish or cancel a recording from the pause menu (or with keybinds).
- Optional automatic start when joining a world/server and automatic finish when leaving.
- Works in singleplayer and multiplayer. On servers the exact network stream is recorded, including all mod packets
  (GregTech machines, cables, etc. look exactly like they did).
- Starting mid-session records a snapshot of the current world (chunks, tile entities, entities, player list,
  scoreboard, weather and time). In singleplayer, tile entities that sync through their own mod packets are refreshed
  from the integrated server so they are captured too.
- The recording player is reconstructed from the client (smooth movement, head rotation, arm swings, equipment,
  sneaking/sprinting/item use, riding, sleeping, hurt/death animations and block breaking progress).
- First-person HUD (hotbar, health, food, armor, XP) is recorded and shown while spectating the recording player.
- Up to four marker keybinds; dimension changes are marked automatically.
- Crash safe: recordings are written continuously and can be recovered after a crash.
- The block/item id mapping (which differs per world in 1.7.10) is stored and restored for playback.
- Forge Multipart microblocks that are already in the world when a recording starts are recorded too (Forge Multipart
  only sends them when a chunk is first seen), and replays from older versions rebuild them from their saved data.
- Capes from mods (GregTech, Galacticraft, Galaxy Space and others that change the player's cape texture) are recorded
  with each player and shown in the replay, even though the mod packets or downloads that gave them out are not
  replayed. Capes that only exist as a download are stored in the replay as an image.

### Replay viewer & editor
- Free camera (hold right mouse over the world, WASD/Space/Shift, scroll for speed, Ctrl to boost) with optional
  momentum, axis locks and horizontal/camera-relative movement.
- Play, pause, slow motion and fast forward (0.05x to 64x), skip forwards/backwards, frame stepping, and seeking to any
  point on the timeline. Pausing and slow motion are perfectly smooth (the world's partial tick follows the replay clock).
- Timeline with markers, in/out points, zoom and scroll.
- Keyframe tracks with Smooth (spline), Linear, Ease In/Out, Ease In, Ease Out and Hold interpolation:
  - **Camera** (position, rotation and roll)
  - **FOV**
  - **Time of Day**
  - **Speed** (playback/export speed, for speed ramps and timelapses)
  - **Camera Shake**
  - **Weather** (rain and thunder strength, 0 to 1)
- Keyframe editing: select, multi-select, drag, copy/paste, delete, per-keyframe interpolation, numeric inspector,
  "set to current camera", camera path preview in the world.
- Camera presets that follow a moving player or mob while the replay plays, without keyframes:
  - **Orbit** circles the target at a set radius, height and speed.
  - **Follow** moves with the target at a set offset, which can turn with the target's heading.
  - **Look At** stays in one place and turns to keep the target in view.

  Each preset covers a range of ticks on the camera track, starts from where the camera is when you make it, can
  smooth out the target's movement, and blends into the camera keyframes at both ends. They show in exports.
- Number inputs: drag a number sideways or click it to type a value. Values with a natural range (FOV, time of day,
  roll, pitch, weather, shake, preset radius and angles) use sliders; double-click or right-click a slider to type.
  Playback speed and flying speed can be typed in too.
- Player list: spectate any player (first person, including the recorder's HUD), hide/show players, teleport the
  camera to them; spectate nearby mobs.
- Visuals: toggle chat (which also covers Server Utilities notifications such as backups and claimed chunks), scoreboard, player list, boss bar, entities, players, particles, sky, clouds and nametags;
  override FOV, time of day and weather; night vision; camera roll; camera shake; rule-of-thirds and center guides.
- Editor state (keyframes, in/out points, visuals, export settings) is saved inside the replay file.

### Export
- Renders the replay frame by frame at any resolution and framerate, independent of your real FPS.
- Formats: MP4/MKV (H.264), WebM (VP9), MOV (ProRes 422 HQ), GIF (via ffmpeg) and PNG image sequences (built in, no
  external tools needed).
- Waits for chunk building before saving each frame (vanilla renderer and Angelica 2.1.16). The Angelica 1.0.0-beta66b
  in GT New Horizons 2.8.4 is not recognised by this wait, so the first second or so of an export can still show chunks
  loading in.
- Optional extra frames per saved frame for shader packs that use temporal accumulation (TAA).
- Speed keyframes, optional hidden nametags, progress display, ESC to cancel.

## Shaders and rendering compatibility

FlashbackNH never replaces any renderer. Everything visual goes through the normal camera setup and render loop:

- FOV, roll and shake are applied in the regular camera transform, so shader packs see the same view.
- Exports read the finished frame (after shader composite passes) from the main framebuffer. The framebuffer is
  resized to the export resolution the same way a window resize does it, which Angelica/Iris already handle.
- Entity hiding is applied at the entity render call, so it is consistent in shadow passes too.
- The camera path preview is never drawn into exports.

If something looks wrong with a specific shader pack, try a few "extra frames" in the export settings first.

## Distant Horizons

If [Distant Horizons for 1.7.10](https://github.com/GTNewHorizons/DistantHorizons) is installed, replays and exports
show the LODs of the recorded world. It is optional, and there is nothing to set up.

Recordings store which DH folder each dimension used. Opening a replay copies that dimension's LOD database to
`flashbacknh/cache/dh` and DH reads the copy with updates turned off, so a replay never changes your real LOD data or
adds replayed chunks to it. The copy is deleted when you exit the replay, or on the next game start at the latest.

Limitations:

- LODs come from the database on your computer as it is when you open the replay, not as it was when you recorded. Areas
  explored later appear in the replay, and changes to the terrain since then show in the distance.
- A replay shows no LODs if that database is missing, for example on another computer or after deleting the world, and
  neither do recordings made before this support was added.
- The first load of each dimension in a replay briefly needs disk space and time for the copy. If there is less free
  space than the database needs, the replay plays without LODs and says why in the log. Very large databases may cause a
  short pause, and this has only been tested with a database of about 150 MB.
- DH has no signal for "LODs finished loading", so exports wait a fixed time after a level loads. If the first frames
  lack LODs, raise `dhLodWaitSeconds` (default 6) in the export section of the config.
- Keeping LODs after a backward seek calls DH internals, as its public API can't do this. If a later DH version changes
  them, a warning is logged and LODs disappear after backward seeks until you reopen the replay; the rest keeps working.
- Shader packs need their own DH support. Complementary Reimagined r5.7.1 and r5.9.3 show LODs in replays and exports.
  Complementary Shaders 4.7.2 does not support DH and shows no LODs, in replays or while playing.
- With a shader pack on, Angelica 2.1.16 and DH stop drawing LODs after any window resize, even in normal play, until the
  shaders are reloaded. Exports handle this themselves by reloading the shaders when the export size differs from the
  window, and again afterwards. If you resize the window during a replay, reload the shaders with Angelica's key.

## What has been tested

Everything below was run in the real game, driven by the test harness described under [Building](#building), with the
results read from screenshots, exported files and logs.

**Environments**

- Forge dev client on Java 8, plain and with Angelica 1.0.0-beta66b and GT5-Unofficial 5.09.51.482.
- The built jar in a copy of the GT New Horizons 2.8.4 pack (311 mods, lwjgl3ify) on Java 25, launched through Prism.
- A local dedicated server (`runServer`, with GT5-Unofficial) for the server tests.
- Shaders: Angelica with no pack and with Complementary Shaders 4.7.2. With Distant Horizons, also Complementary
  Reimagined r5.7.1 and r5.9.3, without Euphoria Patches.
- That same pack with Distant Horizons alpha18, Angelica 2.1.16, lwjgl3ify 3.0.15 and GTNHLib 0.9.47, as DH needs a newer
  Angelica than the pack ships.

**Verified**

- Recording: start, pause, unpause, finish and cancel from the pause menu, the save screen, markers, auto-start on join,
  auto-finish on leaving, and recovery after killing the game mid-recording.
- Server recording: from join and mid-session, including GT machines placed before and during the recording.
- Dimension changes during a recording: playing and seeking across them, with the camera following the player.
- Playback: the *Replays* button (also on the GTNH main menu), the recording player's skin, movement and animations,
  entities, tile entities, play, pause, speed, seeking both ways, and spectating.
- Exit: returns to the replay list and the main menu, and singleplayer and servers work normally afterwards, including
  an existing GTNH world.
- Editor: adding, moving, copying, pasting and deleting keyframes, in/out points, markers, the camera path following
  keyframes, and the visuals panel (FOV, roll, time, weather, hidden entities, guides).
- Export at 1920x1080 and 60 FPS: MP4 and PNG sequences with the expected frame counts and no editor GUI or HUD, with
  and without shaders. On the pack's own Angelica the first second can show chunks loading in (see [Export](#export)).
- Distant Horizons, singleplayer overworld: LODs at replay open, after seeking both ways, in an MP4 export, and live
  again afterwards. A checksum of the real LOD database did not change between opening and exiting the replay. The same
  with Complementary Reimagined r5.7.1 and r5.9.3, the latter also with exports at the window size.
- 0.2.0 features: a GregTech cape (given the way GregTech gives it) and a downloaded cape on the recording player in
  replays and an MP4 export; weather keyframes (rain and thunder strength in between keyframes); orbit and follow
  presets during playback (orbit radius within 0.02 blocks, camera aimed at the target) and in an MP4 export; the
  presets panel and its timeline bars; typing into a number box and into a slider.
- Forge Multipart: pillar and face microblocks placed before a mid-session recording show in its replay, also in a replay
  recorded the old way, and one removed during the recording disappears in the replay without ending it.
- GregTech: machine hulls, basic machines, wires and pipes in replays and exports. Live and replayed views match under
  shaders.
- No crashes and no failed replay packets in the final runs.

**Not tested**

- Clicks with a real mouse and keyboard. The harness sends input to the screens directly, so holding the right mouse
  button to fly and the Ctrl shortcuts themselves have not been exercised, although the actions behind them have.
- Dragging number boxes and sliders with a real mouse (typing into them was tested), Look At presets, presets on
  mobs, and capes of other players on a server.
- Export formats other than MP4 and PNG, cancelling an export with ESC, and other shader packs.
- Long recordings, large bases, public servers and other versions of the pack.
- Distant Horizons on servers, across dimension changes, with other DH or Angelica versions, or with large databases.
- Other addons. FalseTweaks 4.4.5 crashed at startup in the pack with FlashbackNH installed; the cause was not determined.

## Installation

1. Install GT New Horizons (or any Forge 1.7.10 setup with [UniMixins](https://github.com/LegacyModdingMC/UniMixins)).
2. Drop the FlashbackNH jar into your `mods` folder (client only). Use `flashbacknh-<version>.jar`, not the `-dev` or
   `-sources` jar.
3. For video export, install [ffmpeg](https://ffmpeg.org/download.html) and make sure it is on your `PATH`, or set its
   full path as `ffmpegPath` in `config/flashbacknh.cfg` (also editable from the Mods menu). PNG export works without
   ffmpeg.

## Usage

- **Record**: open the pause menu and click *Start Recording*. Use *Pause*/*Finish*/*Cancel* from the same menu.
  Enable *autoStart* in the config to record every session automatically. Finished recordings ask for a name.
- **Watch**: click *Replays* in the top-left corner of the main menu, pick a replay and click *Open Replay*.
- **Edit**: in the editor, hold the right mouse button over the world to fly. *Help → Controls* lists every shortcut.

| Key | Action |
| --- | --- |
| Right mouse (hold) | Look around / fly |
| WASD, Space, Shift | Move the camera (Ctrl to boost, scroll to change speed) |
| Space | Play / pause |
| Left / Right | Step one tick (Shift: one second) |
| Up / Down | Next / previous keyframe |
| I / O | Set in / out point |
| K | Add a camera keyframe |
| M | Add a marker |
| Delete | Delete selected keyframes |
| Ctrl+C / Ctrl+V / Ctrl+A | Copy / paste / select all keyframes |
| Ctrl + drag playhead | Preview the camera path |
| Scroll over the timeline | Zoom |
| F1 | Hide the editor UI |
| Ctrl+E | Export video |
| Esc | Replay menu (exit replay) |

Files live in `.minecraft/flashbacknh/`: `replays/` (replay archives), `exports/` (videos), `recording/` (in-progress
recordings).

## Differences from Flashback

Minecraft 1.7.10 and Forge are very different from modern Fabric, so some things work differently or are missing:

- **Audio is not exported.** 1.7.10's sound engine (Paulscode) has no way to capture its output. Add audio in a video
  editor.
- Video export uses an external ffmpeg instead of bundled native libraries.
- The editor UI is built with Minecraft's own GUI system instead of ImGui.
- Jumping backwards keeps the world and restores only what differs, by replaying the recording from its last world
  snapshot before that point, without rendering. The further that point is from the snapshot, the longer this takes.
  Going back past a pause or dimension change in the recording reloads the world instead, which is slower.
- Replays are not compatible with Flashback's file format (the network protocol is completely different).
- Standing in a nether portal closes any open screen in vanilla, including the save screen. The recording is not lost:
  it can be recovered from the *Recover* button in the replay list.
- Mod HUD elements such as the JourneyMap minimap and WAILA stay visible in the editor view. They do not appear in
  exports.
- Not (yet) implemented: combining replays, export queue, skin replacement, Simple Voice Chat/Bobby integration,
  depth-map/transparent-sky exports.

## Building

Requires JDK 25 for Gradle (the build targets Java 8 bytecode, like every GTNH mod):

```
./gradlew build          # jar in build/libs
./gradlew runClient      # dev client
./gradlew test           # unit tests
```

`dependencies.gradle` pulls Angelica and GT5-Unofficial into the dev client, at the versions used by GTNH 2.8.4.

`./gradlew runClient -PfbnhDev` enables a test harness (`dev/DevDriver`, inactive unless `-Dflashbacknh.dev=true` is set).
It runs commands from `fbnh-dev/commands.txt` in the game directory and writes results and screenshots next to it, so
the game can be tested without a person at the keyboard.

## License

FlashbackNH's code is licensed under the MIT license (see `LICENSE`). Flashback is © Moulberry; its name is used here
only to describe the feature set this mod reimplements.
