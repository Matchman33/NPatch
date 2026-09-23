-keep class top.nkbe.npatch.metaloader.LSPAppComponentFactoryStub {
    public static byte[] dex;
    public static boolean hideLibs;
    <init>();
}

# The meta loader is appended to an arbitrary target APK. Keep R8-generated names
# out of the default package so they cannot collide with the target or loader.bin.
-repackageclasses top.nkbe.npatch.metaloader.internal

-keep class * extends androidx.room.Entity {
    <fields>;
}
-keep interface * extends androidx.room.Dao {
    <methods>;
}

-dontwarn androidx.annotation.NonNull
-dontwarn androidx.annotation.Nullable
-dontwarn androidx.annotation.VisibleForTesting
