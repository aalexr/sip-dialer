# SIP Dialer

An Android phone app for SIP calls with a Material 3 Expressive UI. It runs alongside the stock dialer
and does not replace it.

## Stack (open source only)

| Part | Library | License |
|---|---|---|
| UI | Jetpack Compose, Material 3 **1.5** (Expressive) | Apache 2.0 |
| System call integration | `androidx.core:core-telecom` | Apache 2.0 |
| SIP / RTP / codecs | Linphone SDK 5.5 | **GPLv3** |

There are no Google Play Services or Firebase dependencies. Because Linphone is GPLv3,
any distributed build of this app must also be released under the GPLv3.

## Build

```sh
./gradlew assembleDebug        # one APK per ABI in app/build/outputs/apk/debug/
adb install app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Requires JDK 17+ and the Android SDK (`local.properties` → `sdk.dir`).

## Layout

- `sip/SipEngine.kt`: Linphone core: account, registration, calls, call log
- `telecom/TelecomBridge.kt`: mirrors each SIP call into Android Telecom (audio routing, Bluetooth, car, call waiting)
- `call/`: foreground service and CallStyle notifications
- `ui/`: Compose screens: keypad, recents, contacts, account, in-call

## Not done yet

- Incoming calls while the app is killed. This needs push: UnifiedPush (open source) instead of FCM.
- Contact name lookup for incoming calls and recents.
- Video, call transfer, multiple accounts, localisation.
