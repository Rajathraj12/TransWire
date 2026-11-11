# TransWire R8 / ProGuard keep rules

# WebRTC native JNI bindings (com.mesibo.api:webrtc)
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Socket.IO / OkHttp / Engine.IO
-keep class io.socket.** { *; }
-dontwarn io.socket.**
-dontwarn okhttp3.**
-dontwarn okio.**

# ML Kit Barcode Scanning & CameraX
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.zxing.** { *; }

# Room database entities & DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Domain protocol models
-keep class com.example.myapplication.domain.model.** { *; }

