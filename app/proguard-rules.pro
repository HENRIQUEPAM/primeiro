# WebRTC usa JNI: os nomes das classes nativas não podem ser renomeados.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Modelos serializados de/para o Firestore.
-keep class com.portaretrato.app.call.CallInvite { *; }

# ML Kit (deteccao de rostos, pacote recognition/): a build de DEBUG nunca
# minifica, entao este era um risco nunca testado ate a build de release
# entrar em jogo. Sem estas regras, e problema documentado e recorrente
# (https://github.com/googlesamples/mlkit/issues/661): a deteccao facial
# compila limpo e quebra em tempo de execucao com IllegalAccessError /
# ClassNotFoundException, porque o modelo empacotado e carregado por
# reflexao. com.google.android.gms.internal.mlkit_vision_face_bundled_wrapper
# e o pacote interno especifico do play-services-mlkit-face-detection.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.android.gms.internal.mlkit_vision_face_bundled_wrapper.** { *; }
-dontwarn com.google.android.gms.internal.mlkit_vision_face_bundled_wrapper.**

# TensorFlow Lite (embedding facial, pacote recognition/): metodos nativos
# (JNI) o R8 ja preserva sozinho, mas os delegates de aceleracao
# (NNAPI/XNNPACK, usados via Options.setUseNNAPI/setUseXNNPACK) carregam
# classes por reflexao em tempo de execucao.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
