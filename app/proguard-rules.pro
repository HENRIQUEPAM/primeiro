# WebRTC usa JNI: os nomes das classes nativas não podem ser renomeados.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Modelos serializados de/para o Firestore.
-keep class com.portaretrato.app.call.CallInvite { *; }
