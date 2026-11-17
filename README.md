# SIP Dialer

An Android phone app for SIP calls with a Material 3 Expressive UI. It runs alongside the stock dialer
and does not replace it.

## Stack (open source only)

| Part | Library | License |
|---|---|---|
| UI | Jetpack Compose, Material 3 **1.5** (Expressive) | Apache 2.0 |
| System call integration | `androidx.core:core-telecom` | Apache 2.0 |
| SIP / RTP / media | PJSIP 2.17 (pjsua C API, own JNI layer) | **GPLv2+** |
| TLS | OpenSSL 3.5 | Apache 2.0 |
| Opus | libopus 1.5 | BSD |
| AMR-NB / AMR-WB | the phone's own codecs via Android MediaCodec | (platform) |

There are no Google Play Services or Firebase dependencies. Because PJSIP is GPL,
any distributed build of this app must also be released under the GPLv3.

## Codecs

Offer order: **AMR-WB**, Opus, G.722, AMR-NB, G.711 A-law, G.711 µ-law. AMR-WB comes first so a
gateway can pass carrier (VoLTE/VoWiFi) AMR-WB straight through without transcoding. In SIP
the answerer picks, so the server's preference still wins. AMR is only offered when the
phone has a MediaCodec AMR encoder and decoder (logcat `SipEngine` lists the codecs at start).
The negotiated codec shows on the in-call screen.

## Build

```sh
./gradlew assembleDebug        # one APK per ABI in app/build/outputs/apk/debug/
adb install app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Requires JDK 17+, the Android SDK (`local.properties` → `sdk.dir`) with NDK `29.0.14206865`
and CMake `4.1.2`, plus `perl`, `make` and `curl`.

The first build runs `native/build-deps.sh`, which downloads PJSIP, OpenSSL and Opus
(checksums pinned), and builds them as static libraries into `native/build/<abi>/`. This
takes a few minutes per ABI. Later builds skip it. PJSIP build options are in
`native/config_site.h`.

## Layout

- `sip/SipEngine.kt`: SIP user agent: account, registration, calls, call log, tones
- `sip/PjNative.kt` + `cpp/pjsip_jni.c`: JNI bridge to PJSIP's pjsua API
- `native/`: build script and configuration for the native libraries
- `telecom/TelecomBridge.kt`: mirrors each SIP call into Android Telecom (audio routing, Bluetooth, car, call waiting)
- `call/`: foreground service and CallStyle notifications
- `ui/`: Compose screens: keypad, recents, contacts, account, in-call

## Not done yet

- Incoming calls while the app is killed. This needs push: UnifiedPush (open source) instead of FCM.
- Contact name lookup for incoming calls and recents.
- Video, call transfer, multiple accounts, localisation.

## License

SIP Dialer is free software, released under the [GNU General Public License v3.0](LICENSE).
It links PJSIP (GPLv2 or later).
