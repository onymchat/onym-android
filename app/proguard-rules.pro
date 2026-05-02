# OnymSDK loads libonym_sdk_jni.so via JNI; keep the OnymJni class so the
# Java_chat_onym_sdk_internal_OnymJni_* symbols stay resolvable from the
# native side even after R8 minification.
-keep class chat.onym.sdk.internal.OnymJni { *; }
-keep class chat.onym.sdk.** { *; }

# BouncyCastle uses reflection for provider registration; keep its services.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
