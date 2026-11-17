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
- `background/`: keeps the SIP registration open while the app is closed (see below)
- `ui/`: Compose screens: keypad, recents, contacts, account, in-call

## Incoming calls while the app is closed

There is no push yet, so the app keeps its SIP connection open itself. When an account is set up,
`KeepAliveService` runs as a foreground service with a quiet "Ready for incoming calls" notification,
which stops Android from killing the process. It starts again after a reboot or an app update.
About every 10 minutes an alarm wakes the phone to refresh the registration, because the app's timers
stop while the phone is in deep sleep.

For this to work while the phone sleeps (Doze), the app has to be exempt from battery optimisation.
The account screen asks for this. You can turn the feature off there, and you can hide the
"Ready for calls" notification category in the system settings. Some vendors (Xiaomi, Huawei, Samsung
and others) have their own "auto start" or "app sleeping" lists. The app needs to be allowed there too.

## Not done yet

- Push (UnifiedPush, open source, instead of FCM) as a lighter replacement for the always-on connection.
- Contact name lookup for incoming calls and recents.
- Video, call transfer, multiple accounts, localisation.

## License

SIP Dialer is free software, released under the [GNU General Public License v3.0](LICENSE).
It links the Linphone SDK, which is GPLv3 as well.
