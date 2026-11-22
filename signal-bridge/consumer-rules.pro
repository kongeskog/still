# Starting point taken from Signal-Android: JNI, Wire protos and reflection-heavy
# records break under obfuscation, so names are kept.
-dontobfuscate
-keep class org.whispersystems.** { *; }
-keep class org.signal.libsignal.** { *; }
-keep class org.signal.network.** { *; }
-keep class org.signal.core.** { *; }
-keep class org.signal.storageservice.** { *; }
-keep class org.rustls.platformverifier.** { *; }
-keep class kotlin.Metadata { *; }
-keep class * extends com.squareup.wire.Message { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn **

# The calling engine is driven from native code by name.
-keep class org.signal.ringrtc.** { *; }
-keep class org.webrtc.** { *; }
