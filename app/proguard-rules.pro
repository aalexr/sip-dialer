# JNI: native code binds to these names (app/src/main/cpp/pjsip_jni.c).
-keep class app.sipdialer.sip.PjNative { *; }
-keep interface app.sipdialer.sip.PjListener { *; }
-keepclassmembers class * implements app.sipdialer.sip.PjListener { *; }
