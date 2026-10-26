# ProGuard / R8 optimization rules for Mushroom Offline Navigator
-keepclasseswithmembers class * extends android.database.sqlite.SQLiteOpenHelper {
     public <init>(...);
}

# Keep Microsoft ONNX Runtime for Android JNI
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
