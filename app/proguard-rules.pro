# sherpa-onnx JNI 类通过 JNI 反射调用，避免被裁剪
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class com.k2fsa.sherpa.onnx.** {
    native <methods>;
}
