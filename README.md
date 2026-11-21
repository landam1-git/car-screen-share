# Car Screen Share

Mirror your Android phone's screen onto your car's **Android Auto** display — **only while parked**.

## How it works

- **Phone side:** Android's screen-capture API (MediaProjection) records the phone screen.
- **Car side:** the app registers with Android Auto as a navigation app, because only navigation
  apps get a blank drawing surface on the car display. The captured screen is scaled to fit and
  drawn there.
- **Parked-only:** the picture is replaced with *"Paused while driving"* the instant the car
  reports any movement, and comes back 3 seconds after it stops. The car's own speed sensor is
  used when Android Auto provides it, with the phone's GPS as a fallback. If no speed is
  available at all, nothing is shown.
- **Touch:** taps and scrolls on the car screen are passed back to the phone through an
  accessibility service (optional; enable it in the app). The car's action strip also has
  **Back**, **Home** and **Apps** buttons. Touch input is ignored while moving.

## Install

1. Download the APK from the **car-screen-share-debug-apk** artifact of the latest
   *Android build* run under the repo's **Actions** tab (or build it: `./gradlew assembleDebug`).
2. Install it on your phone (allow installs from your browser/file manager when asked).
3. Open **Car Screen Share** and follow the four steps on screen:
   1. Grant location & notification permissions.
   2. *(Optional)* Turn on touch control in Accessibility settings. On Android 13+ a sideloaded
      app's accessibility toggle is greyed out until you go to **App info → ⋮ → Allow restricted
      settings**.
   3. In **Android Auto settings**, tap **Version** 10 times to enable developer mode, then
      **⋮ → Developer settings → Unknown sources**. Without this, Android Auto hides
      non-Play-Store apps.
   4. Tap **Start sharing** and choose *Entire screen*.
4. In the car, open **Car Screen Share** from the Android Auto launcher.

## Limitations

- Audio isn't mirrored (phone audio already plays through the car normally).
- Expect some lag and softness: it's a screen capture redrawn on the car display, not a
  native video stream. Video apps with DRM (Netflix etc.) show black when captured.
- Keep the phone unlocked; the app holds the screen on while mirroring, but a locked phone
  mirrors its lock screen.
- Multi-finger gestures (pinch-zoom) aren't forwarded.
- Google doesn't allow this kind of app on the Play Store; it's for personal sideloading.

## Development

```sh
./gradlew test assembleDebug
```

Unit tests cover the parked/driving gate (`ParkedGate`) and car-to-phone coordinate mapping
(`MirrorGeometry`). Every push builds a debug APK on GitHub Actions.
