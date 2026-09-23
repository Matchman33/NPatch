-keep class top.nkbe.npatch.share.WrapperConfig { *; }
-keep class top.nkbe.npatch.share.PatchConfig { *; }
-keep class top.nkbe.npatch.share.LSPConfig { *; }
-keep class com.android.apksig.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn javax.annotation.**
# Bouncy Castle's optional LDAP/DANE stores are unused by APK signing on Android.
-dontwarn javax.naming.**
