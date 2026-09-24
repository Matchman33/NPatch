package top.nkbe.npatch.patch.wrapper;

import static org.junit.Assert.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.android.apksig.ApkVerifier;
import pxb.android.axml.AxmlReader;
import pxb.android.axml.AxmlVisitor;
import pxb.android.axml.AxmlWriter;
import pxb.android.axml.NodeVisitor;
import top.nkbe.npatch.share.WrapperConfig;

public class WrapperPackerTest {
    private static final String NS = "http://schemas.android.com/apk/res/android";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void preservesBrandingAndNormalizesComponentIdentity() throws Exception {
        byte[] result = new WrapperManifest(manifest(null)).rewrite("example.original.wrapped");
        List<String> attributes = attributes(result);
        assertTrue(attributes.contains("application:icon=2130771969"));
        assertTrue(attributes.contains("application:roundIcon=2130771970"));
        assertTrue(attributes.contains("application:label=2130771971"));
        assertTrue(attributes.contains("application:name=example.original.App"));
        assertTrue(attributes.contains("activity:name=example.original.Main"));
        assertTrue(attributes.contains("activity-alias:targetActivity=example.original.Main"));
        assertTrue(attributes.contains("application:appComponentFactory=" + WrapperConfig.FACTORY));
        assertTrue(attributes.contains("manifest:package=example.original.wrapped"));
        assertTrue(attributes.contains("provider:authorities=example.original.wrapped.provider;example.original.wrapped.provider.id" + digestSuffix()));
        assertTrue(attributes.contains("permission:name=example.original.wrapped.permission.LOCAL"));
        assertTrue(attributes.contains("uses-permission:name=example.original.wrapped.permission.LOCAL"));
        assertTrue(attributes.contains("activity:permission=example.original.wrapped.permission.LOCAL"));
    }

    @Test public void rejectsUnsupportedInputsAndInvalidPackage() throws Exception {
        for (String reason : List.of("split", "isolatedProcess", "useAppZygote", "sharedUserId", "splits.required", "wrapped")) {
            assertThrows(reason, IOException.class, () -> new WrapperManifest(manifest(reason)));
        }
        assertTrue(attributes(new WrapperManifest(manifest(null)).rewrite("example.original")).contains("manifest:package=example.original"));
        assertThrows(IOException.class, () -> new WrapperManifest(manifest(null)).rewrite("invalid package"));
    }

    @Test public void producesSignedWrapperWithUnchangedEmbeddedApkAndFilename() throws Exception {
        File input = input("Sample.apk", false);
        File output = new File(temporary.newFolder("output"), input.getName());
        pack(input, output);
        assertEquals(input.getName(), output.getName());
        assertTrue(new ApkVerifier.Builder(output).setMinCheckedPlatformVersion(28).build().verify().isVerified());
        try (ZipFile zip = new ZipFile(output)) {
            assertArrayEquals(Files.readAllBytes(input.toPath()), zip.getInputStream(zip.getEntry(WrapperConfig.APK_PATH)).readAllBytes());
            assertArrayEquals(loader(), zip.getInputStream(zip.getEntry("classes3.dex")).readAllBytes());
            assertArrayEquals(new byte[] {1, 2, 3}, zip.getInputStream(zip.getEntry("classes.dex")).readAllBytes());
            assertArrayEquals(new byte[] {7, 8, 9}, zip.getInputStream(zip.getEntry("classes2.dex")).readAllBytes());
            assertArrayEquals(new byte[] {4, 5, 6}, zip.getInputStream(zip.getEntry("assets/example.bin")).readAllBytes());
            assertNotNull(zip.getEntry(WrapperConfig.CONFIG_PATH));
            assertNotNull(zip.getEntry("assets/npatch/loader.bin"));
            assertFalse(com.google.gson.JsonParser.parseString(new String(zip.getInputStream(zip.getEntry(WrapperConfig.CONFIG_PATH)).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().get("signatureCompat").getAsBoolean());
            assertFalse(zip.stream().anyMatch(entry -> entry.getName().contains("pine")));
            List<String> attrs = attributes(zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readAllBytes());
            assertTrue(attrs.contains("application:label=2130771971"));
            assertTrue(attrs.contains("manifest:package=example.original"));
            assertTrue(attrs.contains("provider:authorities=example.original.provider;vendor.fixed"));
            var runtimeConfig = com.google.gson.JsonParser.parseString(new String(zip.getInputStream(zip.getEntry("assets/npatch/config.json")).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(runtimeConfig.get("standalone").getAsBoolean());
            assertFalse(runtimeConfig.get("useManager").getAsBoolean());
            assertEquals(0, runtimeConfig.get("sigBypassLevel").getAsInt());
            assertEquals("example.original", runtimeConfig.get("originalPackage").getAsString());
        }
    }

    @Test public void neverOverwritesInputOrExistingOutput() throws Exception {
        File input = input("Original.apk", false);
        byte[] original = Files.readAllBytes(input.toPath());
        assertThrows(IOException.class, () -> pack(input, input));
        File output = temporary.newFile("Existing.apk");
        Files.write(output.toPath(), new byte[] {10, 20});
        assertThrows(IOException.class, () -> pack(input, output));
        assertArrayEquals(original, Files.readAllBytes(input.toPath()));
        assertArrayEquals(new byte[] {10, 20}, Files.readAllBytes(output.toPath()));
    }

    @Test public void retainsResourceTableBytesAndPackageNamespace() throws Exception {
        File input = new File(temporary.getRoot(), "Resources.apk");
        var buffer = java.nio.ByteBuffer.allocate(300).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) 2).putShort((short) 12).putInt(300).putInt(1);
        buffer.putShort((short) 0x0200).putShort((short) 288).putInt(288).putInt(0x7f);
        buffer.put("example.original".getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        byte[] table = buffer.array();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(input.toPath()))) {
            entry(zip, "AndroidManifest.xml", manifest(null));
            entry(zip, "resources.arsc", table);
        }
        File output = new File(temporary.getRoot(), "Resources-wrapped.apk");
        pack(input, output);
        try (ZipFile zip = new ZipFile(output)) {
            ZipEntry resources = zip.getEntry("resources.arsc");
            assertEquals(ZipEntry.STORED, resources.getMethod());
            assertArrayEquals(table, zip.getInputStream(resources).readAllBytes());
            assertArrayEquals(Files.readAllBytes(input.toPath()),
                    zip.getInputStream(zip.getEntry(WrapperConfig.APK_PATH)).readAllBytes());
        }
    }

    @Test public void preservesStoredAssetsForAssetFileDescriptors() throws Exception {
        File input = new File(temporary.getRoot(), "Stored.apk");
        byte[] content = new byte[8192];
        java.util.Arrays.fill(content, (byte) 42);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(input.toPath()))) {
            entry(zip, "AndroidManifest.xml", manifest(null));
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(content);
            ZipEntry asset = new ZipEntry("assets/mapped.bin");
            asset.setMethod(ZipEntry.STORED);
            asset.setSize(content.length);
            asset.setCrc(crc.getValue());
            zip.putNextEntry(asset);
            zip.write(content);
            zip.closeEntry();
        }
        File output = new File(temporary.getRoot(), "Stored-wrapped.apk");
        pack(input, output);
        try (ZipFile zip = new ZipFile(output)) {
            assertEquals(ZipEntry.STORED, zip.getEntry("assets/mapped.bin").getMethod());
            assertArrayEquals(content, zip.getInputStream(zip.getEntry("assets/mapped.bin")).readAllBytes());
        }
    }

    @Test public void rejectsReservedAssetsWithoutPublishingPartialApk() throws Exception {
        File input = input("Reserved.apk", true);
        File output = new File(temporary.getRoot(), "result.apk");
        assertThrows(IOException.class, () -> pack(input, output));
        assertFalse(output.exists());
        assertFalse(java.util.Arrays.stream(temporary.getRoot().listFiles()).anyMatch(f -> f.getName().startsWith("wrapper-")));
    }

    @Test public void copiesCompressedAssetsWithoutRecompression() throws Exception {
        File input = new File(temporary.getRoot(), "Compressed.apk");
        byte[] content = new byte[8192];
        java.util.Arrays.fill(content, (byte) 42);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(input.toPath()))) {
            zip.setLevel(0);
            entry(zip, "AndroidManifest.xml", manifest(null));
            entry(zip, "assets/compressed.bin", content);
        }
        File output = new File(temporary.getRoot(), "Compressed-wrapped.apk");
        pack(input, output);
        try (ZipFile original = new ZipFile(input); ZipFile wrapped = new ZipFile(output)) {
            ZipEntry asset = wrapped.getEntry("assets/compressed.bin");
            assertEquals(ZipEntry.DEFLATED, asset.getMethod());
            assertEquals(original.getEntry(asset.getName()).getCompressedSize(), asset.getCompressedSize());
            assertArrayEquals(content, wrapped.getInputStream(asset).readAllBytes());
        }
    }

    private void pack(File input, File output) throws Exception {
        try (var key = new FileInputStream(new File(System.getProperty("repoRoot"), "jar/src/main/assets/npatch.key"))) {
            WrapperPacker.pack(input, output, "example.original", loader(), WrapperSigning.builtin(key), runtime(), false, ignored -> {});
        }
    }

    @Test public void embedsVerifiedRuntimeAndRetainsOriginalWhenSignatureModeIsEnabled() throws Exception {
        File unsigned = input("Source.apk", false);
        File input = new File(temporary.getRoot(), "Signed.apk");
        var signer = testSigner();
        try (var source = com.android.tools.build.apkzlib.zip.ZFile.openReadOnly(unsigned);
             var zip = com.android.tools.build.apkzlib.zip.ZFile.openReadWrite(input)) {
            new com.android.tools.build.apkzlib.sign.SigningExtension(com.android.tools.build.apkzlib.sign.SigningOptions.builder()
                    .setKey(signer.getPrivateKey()).setCertificates(new java.security.cert.X509Certificate[] {(java.security.cert.X509Certificate) signer.getCertificate()})
                    .setMinSdkVersion(28).setV1SigningEnabled(false).setV2SigningEnabled(true).build()).register(zip);
            zip.mergeFrom(source, ignored -> false);
        }
        assertTrue(new ApkVerifier.Builder(input).setMinCheckedPlatformVersion(28).build().verify().isVerified());
        File output = new File(temporary.getRoot(), "Hooked.apk");
        byte[] runtime = runtime();
        WrapperPacker.pack(input, output, "example.original", loader(), signer, runtime, true, ignored -> {});
        try (ZipFile zip = new ZipFile(output)) {
            var config = com.google.gson.JsonParser.parseString(new String(zip.getInputStream(zip.getEntry(WrapperConfig.CONFIG_PATH)).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(config.get("signatureCompat").getAsBoolean());
            assertEquals(WrapperConfig.HOOK_RUNTIME, config.get("hookRuntime").getAsString());
            for (var library : WrapperRuntime.read(runtime).entrySet()) {
                byte[] contents = zip.getInputStream(zip.getEntry(WrapperConfig.RUNTIME_PREFIX + library.getKey())).readAllBytes();
                assertArrayEquals(library.getValue(), contents);
                assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(contents)),
                        config.getAsJsonObject("runtimeSha256").get(library.getKey()).getAsString());
            }
            assertArrayEquals(Files.readAllBytes(input.toPath()), zip.getInputStream(zip.getEntry(WrapperConfig.APK_PATH)).readAllBytes());
        }
    }

    @Test public void rejectsUnsignedSourceOrMissingRuntimeWithoutPublishing() throws Exception {
        File input = input("Unsigned.apk", false);
        File output = new File(temporary.getRoot(), "Rejected.apk");
        assertThrows(IOException.class, () -> WrapperPacker.pack(input, output, "example.original", loader(), testSigner(), runtime(), true, ignored -> {}));
        assertThrows(IOException.class, () -> WrapperPacker.pack(input, output, "example.original", loader(), testSigner(), new byte[0], false, ignored -> {}));
        assertFalse(output.exists());
    }

    @Test public void embedsPerPackageGadgetAndRecordsGeneratedConfiguration() throws Exception {
        File input = input("Gadget.apk", false);
        File output = new File(temporary.getRoot(), "Gadget-wrapped.apk");
        byte[] script = "console.log('wrapper gadget');".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        WrapperGadget gadget = WrapperGadget.script(gadgetElf("arm64-v8a"), script);
        WrapperPacker.pack(input, output, "example.original", loader(), testSigner(), runtime(),
                false, gadget, ignored -> {});

        try (ZipFile zip = new ZipFile(output)) {
            String prefix = WrapperConfig.RUNTIME_PREFIX + WrapperConfig.GADGET_PREFIX + "arm64-v8a/";
            assertNotNull(zip.getEntry(prefix + WrapperConfig.GADGET_LIBRARY));
            assertNotNull(zip.getEntry(prefix + WrapperConfig.GADGET_CONFIG));
            assertArrayEquals(script, zip.getInputStream(zip.getEntry(prefix + WrapperConfig.GADGET_SCRIPT)).readAllBytes());
            var config = com.google.gson.JsonParser.parseString(new String(
                    zip.getInputStream(zip.getEntry(WrapperConfig.CONFIG_PATH)).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(config.get("gadgetEnabled").getAsBoolean());
            assertEquals("arm64-v8a", config.get("gadgetAbi").getAsString());
            assertEquals("script", config.get("gadgetMode").getAsString());
        }
    }

    private java.security.KeyStore.PrivateKeyEntry testSigner() throws Exception {
        try (var key = new FileInputStream(new File(System.getProperty("repoRoot"), "jar/src/main/assets/npatch.key"))) {
            return WrapperSigning.builtin(key);
        }
    }

    private byte[] runtime() throws IOException {
        return Files.readAllBytes(new File(System.getProperty("repoRoot"), "out/assets/release/wrapper/runtime.zip").toPath());
    }

    private byte[] loader() throws IOException {
        return Files.readAllBytes(new File(System.getProperty("repoRoot"), "out/assets/release/wrapper/loader.dex").toPath());
    }

    private byte[] gadgetElf(String abi) {
        byte[] elf = new byte[20];
        elf[0] = 0x7f;
        elf[1] = 'E';
        elf[2] = 'L';
        elf[3] = 'F';
        elf[4] = 2;
        elf[5] = 1;
        int machine = abi.equals("arm64-v8a") ? 183 : 62;
        elf[18] = (byte) machine;
        elf[19] = (byte) (machine >>> 8);
        return elf;
    }

    private File input(String filename, boolean reserved) throws Exception {
        File file = new File(temporary.getRoot(), filename);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            entry(zip, "AndroidManifest.xml", manifest(null));
            entry(zip, "classes.dex", new byte[] {1, 2, 3});
            entry(zip, "classes2.dex", new byte[] {7, 8, 9});
            entry(zip, "assets/example.bin", new byte[] {4, 5, 6});
            if (reserved) entry(zip, WrapperConfig.APK_PATH, new byte[] {1});
        }
        return file;
    }

    private static void entry(ZipOutputStream output, String name, byte[] contents) throws IOException {
        output.putNextEntry(new ZipEntry(name)); output.write(contents); output.closeEntry();
    }

    private static byte[] manifest(String unsupported) throws IOException {
        AxmlWriter writer = new AxmlWriter();
        writer.ns("android", NS, 1);
        NodeVisitor root = writer.child(null, "manifest");
        root.attr(null, "package", -1, NodeVisitor.TYPE_STRING, "example.original");
        root.attr(NS, "versionCode", 0x0101021b, NodeVisitor.TYPE_FIRST_INT, 1);
        if ("split".equals(unsupported)) root.attr(null, "split", -1, NodeVisitor.TYPE_STRING, "config.en");
        if ("sharedUserId".equals(unsupported)) root.attr(NS, "sharedUserId", 0x0101000b, NodeVisitor.TYPE_STRING, "example.shared");
        NodeVisitor sdk = root.child(null, "uses-sdk");
        sdk.attr(NS, "minSdkVersion", 0x0101020c, NodeVisitor.TYPE_FIRST_INT, 28);
        sdk.end();
        NodeVisitor permission = root.child(null, "permission");
        str(permission, "name", 0x01010003, "example.original.permission.LOCAL"); permission.end();
        NodeVisitor use = root.child(null, "uses-permission");
        str(use, "name", 0x01010003, "example.original.permission.LOCAL"); use.end();
        NodeVisitor app = root.child(null, "application");
        str(app, "name", 0x01010003, ".App");
        app.attr(NS, "icon", 0x01010002, NodeVisitor.TYPE_REFERENCE, 0x7f010001);
        app.attr(NS, "roundIcon", 0x0101052c, NodeVisitor.TYPE_REFERENCE, 0x7f010002);
        app.attr(NS, "label", 0x01010001, NodeVisitor.TYPE_REFERENCE, 0x7f010003);
        if ("splits.required".equals(unsupported) || "wrapped".equals(unsupported)) {
            NodeVisitor meta = app.child(null, "meta-data");
            str(meta, "name", 0x01010003, "wrapped".equals(unsupported) ? WrapperConfig.MARKER : "com.android.vending.splits.required");
            meta.attr(NS, "value", 0x01010024, NodeVisitor.TYPE_INT_BOOLEAN, true); meta.end();
        }
        NodeVisitor activity = app.child(null, "activity");
        str(activity, "name", 0x01010003, "Main");
        str(activity, "permission", 0x01010006, "example.original.permission.LOCAL"); activity.end();
        NodeVisitor alias = app.child(null, "activity-alias");
        str(alias, "name", 0x01010003, ".Launcher");
        str(alias, "targetActivity", 0x01010202, ".Main"); alias.end();
        NodeVisitor provider = app.child(null, "provider");
        str(provider, "name", 0x01010003, ".Provider");
        str(provider, "authorities", 0x01010018, "example.original.provider;vendor.fixed"); provider.end();
        NodeVisitor service = app.child(null, "service");
        str(service, "name", 0x01010003, ".Service");
        if ("isolatedProcess".equals(unsupported)) service.attr(NS, unsupported, 0x01010376, NodeVisitor.TYPE_INT_BOOLEAN, true);
        if ("useAppZygote".equals(unsupported)) service.attr(NS, unsupported, 0x0101057c, NodeVisitor.TYPE_INT_BOOLEAN, true);
        service.end(); app.end(); root.end();
        return writer.toByteArray();
    }

    private static String digestSuffix() throws Exception {
        byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest("vendor.fixed".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(java.util.Arrays.copyOf(bytes, 16));
    }

    private static void str(NodeVisitor visitor, String name, int id, String value) {
        visitor.attr(NS, name, id, NodeVisitor.TYPE_STRING, value);
    }

    private static List<String> attributes(byte[] xml) throws IOException {
        List<String> result = new ArrayList<>();
        new AxmlReader(xml).accept(new AxmlVisitor() {
            @Override public NodeVisitor child(String ns, String name) { return visitor(name); }
            NodeVisitor visitor(String tag) {
                return new NodeVisitor() {
                    @Override public void attr(String ns, String name, int id, int type, Object value) { result.add(tag + ":" + name + "=" + value); }
                    @Override public NodeVisitor child(String ns, String name) { return visitor(name); }
                };
            }
        });
        return result;
    }
}
