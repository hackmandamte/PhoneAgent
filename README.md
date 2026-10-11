# Phone Agent (Android)

Autonomous Gemini-powered agent with chat, floating bubble, screen control, YouTube helpers, and **local video tools** (for content you own).

## Version 1.2 – Local video support
- `list_videos` – recent videos on the device
- `video_info` – duration, resolution, codecs
- `trim_video` – cut a time range (fast copy when possible)
- `split_video` – cut into sequential segments of N seconds
- `open_video` / `share_video` – open or share a local file (including to YouTube app)
- Uses maintained FFmpeg-Kit (full). APK will be larger (~40–100 MB depending on ABIs).

## Build the APK (GitHub Actions)
1. Create a new GitHub repo and upload everything in this folder (keep `.github/workflows/build.yml`).
2. Open the repo → Actions → "Build APK".
3. Download the `PhoneAgent-apk` artifact and install `app-debug.apk`.
4. On first run, grant **Photos and videos** / storage permission when asked.

## First run
- Paste your Gemini API key (aistudio.google.com).
- Turn on Screen control → enable "Phone Agent" in Accessibility settings.
- Turn on Floating bubble → allow "Display over other apps".

## Typical workflow for your own videos
1. `list_videos` → pick a file path
2. (optional) `trim_video` or `split_video` to create pieces
3. `draft_upload` for title / description / tags
4. `share_video` or `youtube_studio` → finish the upload yourself in the official app

## Tools overview
**Local video (own content only):** list_videos, video_info, trim_video, split_video, open_video, share_video  
**YouTube:** youtube_search, youtube_open, youtube_studio, youtube_channel, draft_upload  
**General:** open_app, list_apps, open_url, web_fetch, notify, speak, battery, datetime, clipboard, share_text, torch, alarms/timers, SMS/dial, settings, files, remember/recall, wait  
**Screen control:** screen_read, tap_text, tap_xy, type_text, scroll, press  

## Notes
- Video tools are intended only for content you own / have rights to.
- High step limit with history trimming. Use Stop if needed.
- API key stored unencrypted in private app storage.
