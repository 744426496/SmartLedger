# Add project specific ProGuard rules here.
# ML Kit model downloading is handled by the SDK; keep its model files.
-keep class com.google.mlkit.** { *; }
-keepattributes Signature
