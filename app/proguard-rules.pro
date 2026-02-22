# ══════════════════════════════════════════════════════════════
#  proguard-rules.pro
#  Keep rules required for the certificate pinning + nonce security layer
# ══════════════════════════════════════════════════════════════

# ── OkHttp / Retrofit ────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepattributes Signature
-keepattributes Exceptions
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# Keep Retrofit service interfaces and their annotations
-keep interface al.gov.voting.api.** { *; }

# ── Security classes — must not be renamed (Keystore key alias strings) ──────
-keep class com.example.albanianidverification.security.NonceManager      { *; }
-keep class com.example.albanianidverification.security.TokenManager      { *; }
-keep class com.example.albanianidverification.security.ApiClient         { *; }
#-keep class com.example.albanianidverification.SecurityChecker   { *; }

# ── API models — Gson serialisation requires field names to survive ──────────
-keep class al.gov.voting.api.models.** { *; }
-keepclassmembers class al.gov.voting.api.models.** { *; }

# ── JMRTD / Bouncy Castle — NFC chip reading ─────────────────────────────────
-keep class org.jmrtd.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.jmrtd.**
-dontwarn org.bouncycastle.**

# ── TensorFlow Lite — liveness detection model ───────────────────────────────
-keep class org.tensorflow.** { *; }
-dontwarn org.tensorflow.**

# ── Play Integrity ────────────────────────────────────────────────────────────
-keep class com.google.android.play.core.integrity.** { *; }