# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep JMRTD classes
-keep class org.jmrtd.** { *; }
-keep class net.sf.scuba.** { *; }

# Keep Bouncy Castle classes
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep ML Kit classes
-keep class com.google.mlkit.** { *; }

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep NFC related classes
-keep class android.nfc.** { *; }
