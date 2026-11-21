# FreeClip for Android

Free, offline AI clipper for your phone: pick a long video, get vertical 9:16 clips with
word-by-word captions and a virality-style score. No account, no watermark, no limits.

## Get the APK
1. Open the **Actions** tab, open the latest green run, and download **FreeClip-APK** (or open **Releases** -> *FreeClip latest build* -> `FreeClip.apk`).
2. Install it on your phone (allow "install unknown apps" for your browser/file manager when asked).

## Use it
Paste a link (YouTube, Google Drive, Dropbox or a direct video link) and tap *Get clips in 1 click*, or upload a video from the phone.
Then preview and trim the suggested clips, tick the ones you like and *Export*. You can also share a YouTube video to FreeClip from the YouTube app.
Clips are saved to the Gallery in `Movies/FreeClip`.

The first analysis downloads a 40 MB English speech model once (needs internet). After that it works offline.

## How it works
* Audio is decoded and transcribed on the phone with Vosk (word timestamps).
* Moments are scored on hook, emotion, pace, loudness, dead air and clean start.
* Clips are cut, center-cropped to 1080x1920 and captioned with Media3 Transformer.

## Limits of this first version
Link import for YouTube is best-effort (YouTube changes often; if it fails, use Upload). Only use videos you own or have permission to clip. English speech only, center crop (no face tracking yet), no split-screen / gaming layouts yet.
