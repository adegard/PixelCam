# PixelCam

An Android camera app that takes better pictures, especially on Pixel phones.
It mimics the "latest generation" iOS camera feel with built-in photographic
styles (color grading) and real on-device scene modes powered by the CameraX
vendor extensions found on Pixel devices.

## Features

- **iOS-style photographic styles** — Standard, Vibrant, Warm, Cool, Rich
  Contrast and Mono applied as subtle color grading. Pick a style before you
  shoot, or adjust it after capture before saving.
- **HDR mode** — multi-exposure high dynamic range via the CameraX extension.
- **Night mode** — low-light multi-frame enhancement via the CameraX extension.
- **Portrait mode** — subject-in-focus background bokeh via the CameraX BOKEH
  extension.
- **Smart default processing** — a subtle punch (saturation + contrast + a hint
  of warmth) applied to every shot by default.
- Front / back camera switch and flash toggle.
- Photos are saved into `Pictures/PixelCam` in your gallery, shareable from the
  app.

> If a mode's vendor extension is not available on a device (only Pixel and a
> few other devices implement them), the app falls back to a regular high
> quality capture and marks the mode as unsupported.

## Getting the APK

The repo builds the APK automatically with GitHub Actions.

1. Open the **Actions** tab of this repository.
2. Select the latest **Build APK** workflow run.
3. Download the `PixelCam-debug-apk` artifact and install the APK on your
   device (allow "install from unknown sources").

You can also run the workflow manually from the Actions tab with
"Run workflow".

## Building locally

Requirements: JDK 17 and an Android SDK.

```
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`.

## How it works

- `CameraController` binds CameraX `Preview` + `ImageCapture`. For HDR / Night /
  Portrait it queries `ExtensionsManager` and binds the extension-enabled
  camera selector, which activates the Pixel vendor extension for that mode.
- `PhotographicStyle` defines each style as a combination of color matrices
  (saturation, contrast/brightness and warm/cool tint) — the same technique
  iOS "Photographic Styles" use under the hood.
- After capture, `PhotoProcessor` re-encodes the image with the selected style
  and inserts it into `MediaStore`.

## License

Apache-2.0
