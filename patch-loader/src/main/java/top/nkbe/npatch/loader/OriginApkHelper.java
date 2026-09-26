package top.nkbe.npatch.loader;

import static top.nkbe.npatch.share.Constants.ORIGINAL_APK_ASSET_PATH;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import top.nkbe.npatch.loader.util.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class OriginApkHelper {

    private static final String TAG = "NPatch-ApkHelper";
    private static final int PER_USER_RANGE = 100000;
    private static final String NATIVE_CACHE_COMPLETE = ".complete";
    private static final Map<Path, FileChannel> originLeases = new HashMap<>();

    public static Path prepareOriginApk(ApplicationInfo appInfo, ClassLoader baseClassLoader) throws IOException {
        return prepareOriginApk(appInfo, baseClassLoader, null);
    }

    public static Path prepareOriginApk(ApplicationInfo appInfo, ClassLoader baseClassLoader, String expectedDigest) throws IOException {
        if (expectedDigest != null) return prepareVerifiedOrigin(appInfo, expectedDigest);
        Path internalOriginDir = Paths.get(appInfo.dataDir, "cache/code_cache/");
        long sourceCrc = getOriginalApkCrc(appInfo.sourceDir);

        Path internalCacheApk = internalOriginDir.resolve(sourceCrc + ".apk");

        if (!Files.exists(internalOriginDir)) {
            Files.createDirectories(internalOriginDir);
        }

        if (!Files.exists(internalCacheApk)) {
            Log.i(TAG, "Extracting origin.apk from assets.");
            FileUtils.deleteFolderIfExists(internalOriginDir);
            Files.createDirectories(internalOriginDir);

            try (InputStream is = baseClassLoader.getResourceAsStream(ORIGINAL_APK_ASSET_PATH)) {
                if (is == null) throw new IOException("Original APK not found in assets");
                Files.copy(is, internalCacheApk);
            }
        } else {
            Log.d(TAG, "Internal cache hit: " + internalCacheApk);
        }

        try {
            internalCacheApk.toFile().setWritable(false);
        } catch (Exception ignored) {
        }

        return internalCacheApk;
    }

    public static Path prepareNativeLibraryDir(ApplicationInfo appInfo, Path originApkPath, String patchedApkPath) throws IOException {
        Path nativeRoot = Paths.get(appInfo.dataDir, "cache/native/host/");
        List<String> apkPaths = new ArrayList<>();
        apkPaths.add(originApkPath.toString());
        if (appInfo.splitSourceDirs != null) {
            for (String splitSourceDir : appInfo.splitSourceDirs) {
                if (splitSourceDir != null && !splitSourceDir.isEmpty()) {
                    apkPaths.add(splitSourceDir);
                }
            }
        }
        if (patchedApkPath != null
                && !patchedApkPath.isEmpty()
                && !patchedApkPath.equals(originApkPath.toString())) {
            apkPaths.add(patchedApkPath);
        }

        String stamp = buildNativeLibraryStamp(apkPaths);
        Path targetDir = nativeRoot.resolve(stamp);
        if (hasNativeLibraries(targetDir)) {
            return targetDir;
        }

        // Several app processes may bootstrap at once. Serialize extraction so no process can
        // observe a directory after only the first library has been written and permanently
        // mistake that partial cache for a complete one.
        Path nativeCacheDir = nativeRoot.getParent();
        Files.createDirectories(nativeCacheDir);
        Path lockPath = nativeCacheDir.resolve("host.lock");
        try (FileChannel lockChannel = FileChannel.open(lockPath,
                                                        StandardOpenOption.CREATE,
                                                        StandardOpenOption.WRITE);
             FileLock ignored = lockChannel.lock()) {
            if (hasNativeLibraries(targetDir)) {
                return targetDir;
            }

            FileUtils.deleteFolderIfExists(nativeRoot);
            Files.createDirectories(targetDir);

            String[] abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
            for (String abi : abis) {
                boolean extractedAny = false;
                for (String apkPath : apkPaths) {
                    extractedAny |= extractNativeLibrariesForAbi(apkPath, abi, targetDir);
                }
                if (extractedAny) {
                    makeNativeLibrariesReadOnly(targetDir);
                    Files.write(targetDir.resolve(NATIVE_CACHE_COMPLETE), new byte[0],
                                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    Log.i(TAG, "Prepared native libraries for " + abi + " at " + targetDir);
                    return targetDir;
                }
            }

            FileUtils.deleteFolderIfExists(targetDir);
            return null;
        }
    }

    public static long getOriginalApkCrc(String sourceDir) throws IOException {
        try (ZipFile sourceFile = new ZipFile(sourceDir)) {
            ZipEntry entry = originalEntry(sourceFile);
            if (entry == null) {
                return 0;
            }
            return entry.getCrc();
        }
    }

    static ZipEntry originalEntry(ZipFile source) {
        ZipEntry legacy = source.getEntry(ORIGINAL_APK_ASSET_PATH);
        return legacy != null ? legacy : source.getEntry(top.nkbe.npatch.share.WrapperConfig.APK_PATH);
    }

    private static synchronized Path prepareVerifiedOrigin(ApplicationInfo appInfo, String expectedDigest) throws IOException {
        long started = System.nanoTime();
        if (!expectedDigest.matches("[0-9a-f]{64}")) throw new IOException("Invalid original APK digest");
        Path directory = Paths.get(appInfo.dataDir, "cache/code_cache");
        Files.createDirectories(directory);
        try (FileChannel channel = FileChannel.open(directory.resolve("original.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock(); ZipFile outer = new ZipFile(appInfo.sourceDir)) {
            ZipEntry entry = originalEntry(outer);
            if (entry == null) throw new IOException("Missing original APK asset");
            Path target = directory.resolve(expectedDigest + ".apk");
            if (!Files.isRegularFile(target) || !expectedDigest.equals(sha256(target))) {
                Path temporary = Files.createTempFile(directory, "origin-", ".tmp");
                try {
                    java.security.MessageDigest digest = newDigest();
                    try (InputStream input = outer.getInputStream(entry); FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                        if (!temporary.toFile().setReadOnly()) throw new IOException("Cannot protect original APK cache");
                        byte[] buffer = new byte[65536];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                            digest.update(buffer, 0, count);
                        }
                        output.getFD().sync();
                    }
                    if (!expectedDigest.equals(hexDigest(digest.digest()))) throw new IOException("Original APK checksum mismatch");
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    if (Files.exists(temporary)) {
                        temporary.toFile().setWritable(true);
                        Files.deleteIfExists(temporary);
                    }
                }
            }
            if (!target.toFile().setReadOnly()) throw new IOException("Cannot protect original APK cache");
            if (!originLeases.containsKey(target)) {
                FileChannel lease = FileChannel.open(leasePath(target), StandardOpenOption.CREATE,
                        StandardOpenOption.READ, StandardOpenOption.WRITE);
                try {
                    lease.lock(0, Long.MAX_VALUE, true);
                    originLeases.put(target, lease);
                } catch (IOException | RuntimeException error) { lease.close(); throw error; }
            }
            cleanupUnusedOrigins(directory, target);
            Log.i(TAG, "Original APK verified in " + (System.nanoTime() - started) / 1_000_000L + " ms");
            return target;
        }
    }

    private static Path leasePath(Path apk) { return apk.resolveSibling(apk.getFileName() + ".lease"); }

    private static void cleanupUnusedOrigins(Path directory, Path current) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path path : files) {
                String name = path.getFileName().toString();
                if (name.matches("origin-.*\\.tmp")) {
                    path.toFile().setWritable(true);
                    Files.deleteIfExists(path);
                } else if (name.matches("[0-9a-f]{64}\\.apk") && !path.equals(current) && !originLeases.containsKey(path)) {
                    // Exclusive acquisition proves no cooperating process still uses this generation.
                    try (FileChannel lease = FileChannel.open(leasePath(path), StandardOpenOption.CREATE,
                            StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                        FileLock lock;
                        try { lock = lease.tryLock(); } catch (OverlappingFileLockException busy) { continue; }
                        if (lock == null) continue;
                        try {
                            path.toFile().setWritable(true);
                            Files.deleteIfExists(path);
                        } finally { lock.release(); }
                    }
                    Files.deleteIfExists(leasePath(path));
                }
            }
        } catch (IOException error) { Log.w(TAG, "Cannot clean old original APK cache", error); }
    }

    // Closing a process lifetime lease is only safe after its class loaders are no longer used.
    static synchronized void releaseOriginLeases() throws IOException {
        for (FileChannel channel : originLeases.values()) channel.close();
        originLeases.clear();
    }

    private static java.security.MessageDigest newDigest() {
        try { return java.security.MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }

    private static String hexDigest(byte[] bytes) {
        StringBuilder result = new StringBuilder(64);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 15, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }

    private static String sha256(Path file) throws IOException {
        var digest = newDigest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hexDigest(digest.digest());
    }

    private static String buildNativeLibraryStamp(List<String> apkPaths) {
        StringBuilder stamp = new StringBuilder();
        for (String apkPath : apkPaths) {
            File file = new File(apkPath);
            if (stamp.length() > 0) {
                stamp.append('_');
            }
            stamp.append(Math.abs(apkPath.hashCode()))
                    .append('-')
                    .append(file.lastModified())
                    .append('-')
                    .append(file.length());
        }
        return stamp.toString();
    }

    private static boolean hasNativeLibraries(Path dir) {
        if (!Files.isDirectory(dir) || !Files.isRegularFile(dir.resolve(NATIVE_CACHE_COMPLETE))) {
            return false;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.so")) {
            return stream.iterator().hasNext();
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean extractNativeLibrariesForAbi(String apkPath, String abi, Path targetDir) {
        boolean extractedAny = false;
        String prefix = "lib/" + abi + "/";
        try (ZipFile apk = new ZipFile(apkPath)) {
            var entries = apk.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(prefix) || !name.endsWith(".so")) {
                    continue;
                }

                Path target = targetDir.resolve(new File(name).getName());
                try (InputStream in = apk.getInputStream(entry);
                     FileOutputStream out = new FileOutputStream(target.toFile())) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                }
                extractedAny = true;
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to extract native libraries from " + apkPath, e);
        }
        return extractedAny;
    }

    private static void makeNativeLibrariesReadOnly(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.so")) {
            for (Path lib : stream) {
                File file = lib.toFile();
                // Keep dlopen targets immutable after extraction; writable native code trips some runtimes.
                file.setReadable(true, false);
                file.setExecutable(true, false);
                file.setWritable(false, false);
            }
        } catch (IOException ignored) {
        }
    }
}
