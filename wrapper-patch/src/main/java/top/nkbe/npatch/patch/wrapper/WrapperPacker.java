package top.nkbe.npatch.patch.wrapper;

import com.android.apksig.ApkVerifier;
import com.android.tools.build.apkzlib.sign.SigningExtension;
import com.android.tools.build.apkzlib.sign.SigningOptions;
import com.android.tools.build.apkzlib.zip.AlignmentRules;
import com.android.tools.build.apkzlib.zip.ZFile;
import com.android.tools.build.apkzlib.zip.ZFileOptions;
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
        if (input.getCanonicalFile().equals(output.getCanonicalFile())) throw new IOException("Output must not overwrite the input APK");
        if (!input.isFile()) throw new IOException("Input APK not found");
        if (output.exists()) throw new IOException("Output already exists: " + output.getName());
        if (loaderDex == null || loaderDex.length < 8 || loaderDex[0] != 'd' || loaderDex[1] != 'e' || loaderDex[2] != 'x') {
            throw new IOException("Missing or invalid standalone loader DEX");
        }
        WrapperManifest manifest = inspect(input);
        WrapperManifest.validatePackage(targetPackage);
        WrapperConfig config = new WrapperConfig();
        config.originalPackage = manifest.packageName;
        config.wrapperPackage = targetPackage;
        config.appComponentFactory = manifest.appComponentFactory;
        java.util.Map<String, byte[]> runtime = WrapperRuntime.read(runtimeZip, gadget);
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
        config.apkSha256 = sha256(input);
        PatchConfig npatch = new PatchConfig(false, false, false, 0,
                signatureCompat ? Constants.SIGBYPASS_EXTREME : Constants.SIGBYPASS_NONE,
                originalSignature, manifest.appComponentFactory, false, true, targetPackage, false, false);
        npatch.standalone = true;
        npatch.embeddedApkSha256 = config.apkSha256;
        npatch.originalPackage = manifest.packageName;
        byte[] npatchBytes = new Gson().toJson(npatch).getBytes(StandardCharsets.UTF_8);
        byte[] rewritten = manifest.rewrite(targetPackage, java.util.Base64.getEncoder().encodeToString(npatchBytes));
        byte[] configBytes = new Gson().toJson(config).getBytes(StandardCharsets.UTF_8);
        File parent = output.getAbsoluteFile().getParentFile();
        Files.createDirectories(parent.toPath());
        File temporary = File.createTempFile("wrapper-", ".apk", parent);
        // ZFile creates the ZIP structure itself.
        Files.delete(temporary.toPath());
        try {
            log.accept("Building " + targetPackage);
            if (!targetPackage.equals(manifest.packageName)) log.accept("Explicit package rename: dynamic resource lookups may be incompatible");
            ZFileOptions options = new ZFileOptions().setNoTimestamps(true).setAlignmentRule(
                    AlignmentRules.compose(AlignmentRules.constantForSuffix(".so", 16384),
                            AlignmentRules.constantForSuffix(WrapperConfig.APK_PATH, 4096),
                            AlignmentRules.constant(4)));
            X509Certificate[] certificates = Arrays.copyOf(signer.getCertificateChain(),
                    signer.getCertificateChain().length, X509Certificate[].class);
            try (ZipFile original = new ZipFile(input);
                 ZFile source = ZFile.openReadOnly(input);
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
                destination.mergeFrom(source, excluded::contains);
                for (String name : storeUncompressed) {
                    ZipEntry entry = original.getEntry(name);
                    try (InputStream contents = original.getInputStream(entry)) {
                        destination.add(name, contents, false);
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
                try (InputStream contents = new FileInputStream(input)) {
                    destination.add(WrapperConfig.APK_PATH, contents, false);
                }
                destination.realign();
                log.accept("Writing and signing wrapper");
            }
            log.accept("Verifying signature and embedded APK");
            ApkVerifier.Result verification = new ApkVerifier.Builder(temporary)
                    .setMinCheckedPlatformVersion(manifest.minSdk).build().verify();
            if (!verification.isVerified()) throw new IOException("Wrapper signature verification failed: " + verification.getErrors());
            try (ZipFile zip = new ZipFile(temporary);
                 InputStream embedded = zip.getInputStream(zip.getEntry(WrapperConfig.APK_PATH))) {
                if (!config.apkSha256.equals(sha256(embedded))) throw new IOException("Embedded APK changed during packaging");
            }
            Files.move(temporary.toPath(), output.toPath());
            log.accept("Created " + output.getName() + " in "
                    + (System.nanoTime() - started) / 1_000_000_000L + " s");
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
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
