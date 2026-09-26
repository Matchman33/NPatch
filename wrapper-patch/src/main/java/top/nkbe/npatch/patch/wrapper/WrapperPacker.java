package top.nkbe.npatch.patch.wrapper;

import com.android.apksig.ApkVerifier;
import com.android.tools.build.apkzlib.sign.SigningExtension;
import com.android.tools.build.apkzlib.sign.SigningOptions;
import com.android.tools.build.apkzlib.zip.AlignmentRules;
import com.android.tools.build.apkzlib.zip.ZFile;
import com.android.tools.build.apkzlib.zip.ZFileOptions;
import com.android.tools.build.apkzlib.bytestorage.ChunkBasedByteStorageFactory;
import com.android.tools.build.apkzlib.bytestorage.OverflowToDiskByteStorageFactory;
import com.android.tools.build.apkzlib.bytestorage.TemporaryDirectory;
import com.google.gson.Gson;
import com.google.common.io.ByteStreams;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import top.nkbe.npatch.share.WrapperConfig;
import top.nkbe.npatch.share.Constants;
import top.nkbe.npatch.share.PatchConfig;

public final class WrapperPacker {
    private WrapperPacker() {}

    public static WrapperManifest inspect(File input) throws IOException {
        try (ZipFile zip = new ZipFile(input)) {
            ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null || entry.getSize() > 16 * 1024 * 1024) throw new IOException("Missing or oversized AndroidManifest.xml");
            try (InputStream manifest = zip.getInputStream(entry)) {
                byte[] bytes = ByteStreams.toByteArray(ByteStreams.limit(manifest, 16 * 1024 * 1024 + 1));
                if (bytes.length > 16 * 1024 * 1024) throw new IOException("Oversized AndroidManifest.xml");
                return new WrapperManifest(bytes);
            }
        }
    }

    public static void pack(File input, File output, String targetPackage, byte[] loaderDex,
                            KeyStore.PrivateKeyEntry signer, byte[] runtimeZip, boolean signatureCompat, Consumer<String> log) throws Exception {
        pack(input, output, targetPackage, loaderDex, signer, runtimeZip, signatureCompat, null, log);
    }

    public static void pack(File input, File output, String targetPackage, byte[] loaderDex,
                            KeyStore.PrivateKeyEntry signer, byte[] runtimeZip, boolean signatureCompat,
                            WrapperGadget gadget, Consumer<String> log) throws Exception {
        pack(input, output, targetPackage, loaderDex, signer, runtimeZip, signatureCompat, gadget,
                log, new PackControl((stage, completed, total) -> {}));
    }

    public static void pack(File input, File output, String targetPackage, byte[] loaderDex,
                            KeyStore.PrivateKeyEntry signer, byte[] runtimeZip, boolean signatureCompat,
                            WrapperGadget gadget, Consumer<String> log, PackControl control) throws Exception {
        control.report(PackControl.Stage.PREPARING, 0, -1);
        if (input.getCanonicalFile().equals(output.getCanonicalFile())) throw new IOException("Output must not overwrite the input APK");
        if (!input.isFile()) throw new IOException("Input APK not found");
        if (output.exists()) throw new IOException("Output already exists: " + output.getName());
        if (loaderDex == null || loaderDex.length < 8 || loaderDex[0] != 'd' || loaderDex[1] != 'e' || loaderDex[2] != 'x') {
            throw new IOException("Missing or invalid standalone loader DEX");
        }
        WrapperManifest manifest = inspect(input);
        WrapperManifest.validatePackage(targetPackage);
        boolean packageRenamed = !targetPackage.equals(manifest.packageName);
        WrapperConfig config = new WrapperConfig();
        config.originalPackage = manifest.packageName;
        config.wrapperPackage = targetPackage;
        config.appComponentFactory = manifest.appComponentFactory;
        java.util.Map<String, byte[]> runtime = WrapperRuntime.read(runtimeZip, gadget);
        File parent = output.getAbsoluteFile().getParentFile();
        Files.createDirectories(parent.toPath());
        long runtimeSize = loaderDex.length;
        for (byte[] bytes : runtime.values()) runtimeSize = Math.addExact(runtimeSize, bytes.length);
        requireSpace(parent, estimateRequiredSpace(input, runtimeSize));
        String originalSignature = null;
        if (signatureCompat) {
            log.accept("Validating original signature for compatibility mode");
            var verification = new ApkVerifier.Builder(input).setMinCheckedPlatformVersion(manifest.minSdk).build().verify();
            if (!verification.isVerified() || verification.getSignerCertificates().size() != 1) {
                throw new IOException("Signature compatibility requires a valid signed original APK");
            }
            StringBuilder encoded = new StringBuilder();
            for (byte value : verification.getSignerCertificates().get(0).getEncoded()) encoded.append(String.format(Locale.ROOT, "%02x", value & 255));
            originalSignature = encoded.toString();
        }
        config.signatureCompat = signatureCompat;
        config.hookRuntime = WrapperConfig.HOOK_RUNTIME;
        config.gadgetEnabled = gadget != null;
        config.gadgetAbi = gadget == null ? null : gadget.abi();
        config.gadgetMode = gadget == null ? null : gadget.mode();
        config.runtimeSha256 = new java.util.TreeMap<>();
        for (var entry : runtime.entrySet()) {
            config.runtimeSha256.put(entry.getKey(), sha256(new ByteArrayInputStream(entry.getValue())));
        }
        long started = System.nanoTime();
        log.accept("Hashing input APK (" + input.length() / (1024 * 1024) + " MiB)");
        try (InputStream contents = control.track(new FileInputStream(input), PackControl.Stage.HASHING, input.length())) {
            config.apkSha256 = sha256(contents);
        }
        PatchConfig npatch = new PatchConfig(false, false, false, 0,
                signatureCompat ? Constants.SIGBYPASS_EXTREME : Constants.SIGBYPASS_NONE,
                originalSignature, manifest.appComponentFactory, false, true, targetPackage, false, false);
        npatch.standalone = true;
        npatch.embeddedApkSha256 = config.apkSha256;
        npatch.originalPackage = manifest.packageName;
        byte[] npatchBytes = new Gson().toJson(npatch).getBytes(StandardCharsets.UTF_8);
        byte[] rewritten = manifest.rewrite(targetPackage, java.util.Base64.getEncoder().encodeToString(npatchBytes));
        byte[] configBytes = new Gson().toJson(config).getBytes(StandardCharsets.UTF_8);
        File workDir = Files.createTempDirectory(parent.toPath(), "wrapper-work-").toFile();
        File temporary = new File(workDir, "result.apk");
        try {
            log.accept("Building " + targetPackage);
            if (packageRenamed) log.accept("Rewriting resource package namespace for " + targetPackage);
            ZFileOptions options = new ZFileOptions().setStorageFactory(new ChunkBasedByteStorageFactory(
                    new OverflowToDiskByteStorageFactory(8L * 1024 * 1024, () -> TemporaryDirectory.fixed(workDir))))
                    .setNoTimestamps(true).setAlignmentRule(
                    AlignmentRules.compose(AlignmentRules.constantForSuffix(".so", 16384),
                            AlignmentRules.constantForSuffix(WrapperConfig.APK_PATH, 4096),
                            AlignmentRules.constant(4)));
            X509Certificate[] certificates = Arrays.copyOf(signer.getCertificateChain(),
                    signer.getCertificateChain().length, X509Certificate[].class);
            try (ZipFile original = new ZipFile(input);
                 ZFile source = ZFile.openReadOnly(input, options);
                 ZFile destination = ZFile.openReadWrite(temporary, options)) {
                new SigningExtension(SigningOptions.builder().setKey(signer.getPrivateKey())
                        .setCertificates(certificates).setMinSdkVersion(manifest.minSdk)
                        .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true).build())
                        .register(destination);
                Set<String> seen = new HashSet<>();
                Set<String> excluded = new HashSet<>();
                Set<String> storeUncompressed = new HashSet<>();
                Set<String> nativeAbis = new HashSet<>();
                Enumeration<? extends ZipEntry> entries = original.entries();
                while (entries.hasMoreElements()) {
                    control.check();
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    String[] segments = name.split("/");
                    if (segments.length == 3 && segments[0].equals("lib") && name.endsWith(".so")) nativeAbis.add(segments[1]);
                    if (!seen.add(name)) throw new IOException("Duplicate APK entry: " + name);
                    if (name.equals(WrapperConfig.APK_PATH) || name.startsWith("assets/wrapper/") || name.startsWith("assets/npatch/")) {
                        throw new IOException("Input uses a reserved wrapper asset path: " + name);
                    }
                    if (name.startsWith("/") || name.contains("\\") || Arrays.asList(name.split("/")).contains("..")) {
                        throw new IOException("Invalid APK entry path: " + name);
                    }
                    if (entry.isDirectory() || name.equals("AndroidManifest.xml")
                            || signatureEntry(name)) {
                        excluded.add(name);
                    } else if (packageRenamed && name.equals("resources.arsc")) {
                        excluded.add(name);
                    } else if (entry.getMethod() != ZipEntry.STORED
                            && (name.endsWith(".so") || name.equals("resources.arsc"))) {
                        excluded.add(name);
                        storeUncompressed.add(name);
                    }
                }
                if (!nativeAbis.isEmpty() && !nativeAbis.contains("arm64-v8a") && !nativeAbis.contains("x86_64")) {
                    throw new IOException("This NPatch runtime requires a 64-bit application ABI");
                }
                log.accept("Copying resources and assets without recompression");
                control.report(PackControl.Stage.COPYING, 0, -1);
                destination.mergeFrom(source, name -> { control.check(); return excluded.contains(name); });
                for (String name : storeUncompressed) {
                    ZipEntry entry = original.getEntry(name);
                    try (InputStream contents = control.track(original.getInputStream(entry), PackControl.Stage.COPYING, entry.getSize())) {
                        destination.add(name, contents, false);
                    }
                }
                if (packageRenamed) {
                    ZipEntry resources = original.getEntry("resources.arsc");
                    if (resources != null) {
                        byte[] resourceTable;
                        try (InputStream contents = control.track(original.getInputStream(resources), PackControl.Stage.COPYING, resources.getSize())) {
                            resourceTable = ByteStreams.toByteArray(contents);
                        }
                        destination.add("resources.arsc", new ByteArrayInputStream(
                                WrapperResources.rewrite(resourceTable, manifest.packageName, targetPackage)), false);
                    }
                }
                destination.add("AndroidManifest.xml", new ByteArrayInputStream(rewritten));
                int dexIndex = 1;
                String loaderEntry = "classes.dex";
                while (seen.contains(loaderEntry)) loaderEntry = "classes" + (++dexIndex) + ".dex";
                destination.add(loaderEntry, new ByteArrayInputStream(loaderDex));
                destination.add(WrapperConfig.CONFIG_PATH, new ByteArrayInputStream(configBytes));
                destination.add(Constants.CONFIG_ASSET_PATH, new ByteArrayInputStream(npatchBytes));
                for (var entry : runtime.entrySet()) {
                    destination.add(WrapperConfig.RUNTIME_PREFIX + entry.getKey(), new ByteArrayInputStream(entry.getValue()), false);
                }
                log.accept("Embedding original APK");
                try (InputStream contents = control.track(new FileInputStream(input), PackControl.Stage.EMBEDDING, input.length())) {
                    destination.add(WrapperConfig.APK_PATH, contents, false);
                }
                destination.realign();
                log.accept("Writing and signing wrapper");
                control.report(PackControl.Stage.SIGNING, 0, -1);
            }
            control.report(PackControl.Stage.VERIFYING, 0, -1);
            log.accept("Verifying signature and embedded APK");
            ApkVerifier.Result verification = new ApkVerifier.Builder(temporary)
                    .setMinCheckedPlatformVersion(manifest.minSdk).build().verify();
            if (!verification.isVerified()) throw new IOException("Wrapper signature verification failed: " + verification.getErrors());
            try (ZipFile zip = new ZipFile(temporary);
                 InputStream embedded = control.track(zip.getInputStream(zip.getEntry(WrapperConfig.APK_PATH)),
                         PackControl.Stage.VERIFYING, input.length())) {
                if (!config.apkSha256.equals(sha256(embedded))) throw new IOException("Embedded APK changed during packaging");
            }
            control.check();
            Files.move(temporary.toPath(), output.toPath());
            log.accept("Created " + output.getName() + " in "
                    + (System.nanoTime() - started) / 1_000_000_000L + " s");
        } finally {
            try (var paths = Files.walk(workDir.toPath())) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) {
                    try { Files.deleteIfExists(path); }
                    catch (IOException error) { log.accept("Cleanup failed: " + path.getFileName() + ": " + error.getMessage()); }
                }
            }
        }
    }

    /** Additional free space; the input snapshot already exists. Includes ZIP spill files. */
    public static long estimateRequiredSpace(File input, long runtimeBytes) throws IOException {
        try (ZipFile zip = new ZipFile(input)) {
            long outer = Math.addExact(input.length(), runtimeBytes);
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                long size = entry.getName().endsWith(".so") || entry.getName().equals("resources.arsc")
                        ? entry.getSize() : entry.getCompressedSize();
                if (size < 0) throw new IOException("Unknown ZIP entry size");
                outer = Math.addExact(outer, Math.addExact(size, 32768L));
            }
            return Math.addExact(Math.multiplyExact(outer, 2L), 64L * 1024 * 1024);
        } catch (ArithmeticException error) { throw new IOException("APK size exceeds supported range", error); }
    }

    public static void requireSpace(File directory, long required) throws IOException {
        long available = directory.getUsableSpace();
        if (available < required) throw new IOException("Insufficient storage: need " + required / (1024 * 1024)
                + " MiB, available " + available / (1024 * 1024) + " MiB");
    }

    private static boolean signatureEntry(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("META-INF/") && (upper.equals("META-INF/MANIFEST.MF")
                || upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"));
    }

    public static String sha256(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) { return sha256(input); }
    }

    private static String sha256(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            StringBuilder value = new StringBuilder(64);
            for (byte b : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", b & 255));
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }
}
