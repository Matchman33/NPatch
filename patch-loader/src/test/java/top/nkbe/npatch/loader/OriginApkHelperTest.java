package top.nkbe.npatch.loader;

import android.content.pm.ApplicationInfo;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class OriginApkHelperTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final byte[] original = {1, 2, 3, 4, 5};

    @Test public void recognizesNewAndLegacyOriginalAssetLayouts() throws Exception {
        for (String path : new String[] {"assets/base.apk", "assets/npatch/origin.apk"}) {
            ApplicationInfo info = archive(path);
            try (ZipFile zip = new ZipFile(info.sourceDir)) {
                assertEquals(zip.getEntry(path).getCrc(), OriginApkHelper.getOriginalApkCrc(info.sourceDir));
            }
        }
    }

    @Test public void publishesVerifiedCacheAndRepairsCorruptionWithoutDeletingOtherGenerations() throws Exception {
        ApplicationInfo info = archive("assets/base.apk");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
        var target = OriginApkHelper.prepareOriginApk(info, null, hash);
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals(target, OriginApkHelper.prepareOriginApk(info, null, hash));
        var older = target.getParent().resolve("previous.apk");
        Files.write(older, new byte[] {9});
        assertTrue(target.toFile().setWritable(true));
        Files.write(target, new byte[] {0, 0, 0, 0, 0});
        OriginApkHelper.prepareOriginApk(info, null, hash);
        assertArrayEquals(original, Files.readAllBytes(target));
        assertArrayEquals(new byte[] {9}, Files.readAllBytes(older));
    }

    @Test public void rejectsWrongDigestWithoutPublishingAnApk() throws Exception {
        ApplicationInfo info = archive("assets/base.apk");
        assertThrows(IOException.class, () -> OriginApkHelper.prepareOriginApk(info, null, "0".repeat(64)));
        try (var entries = Files.list(new File(info.dataDir, "cache/code_cache").toPath())) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().endsWith(".apk")
                    || path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test public void removesUnusedGenerationsButPreservesLeasedGeneration() throws Exception {
        ApplicationInfo info = archive("assets/base.apk");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
        var target = OriginApkHelper.prepareOriginApk(info, null, hash);
        var stale = target.resolveSibling("a".repeat(64) + ".apk");
        var active = target.resolveSibling("b".repeat(64) + ".apk");
        Files.write(stale, original);
        Files.write(active, original);
        try (var channel = java.nio.channels.FileChannel.open(active.resolveSibling(active.getFileName() + ".lease"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            OriginApkHelper.prepareOriginApk(info, null, hash);
            assertFalse(Files.exists(stale));
            assertTrue(Files.exists(active));
        }
        OriginApkHelper.prepareOriginApk(info, null, hash);
        assertFalse(Files.exists(active));
        assertTrue(Files.exists(target));
    }

    private ApplicationInfo archive(String path) throws IOException {
        File directory = temporary.newFolder();
        File outer = new File(directory, "outer.apk");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(outer.toPath()))) {
            zip.putNextEntry(new ZipEntry(path));
            zip.write(original);
        }
        ApplicationInfo info = new ApplicationInfo();
        info.sourceDir = outer.getPath();
        info.dataDir = new File(directory, "data").getPath();
        return info;
    }

    @After public void releaseReadOnlyFixtures() throws IOException {
        OriginApkHelper.releaseOriginLeases();
        try (var files = Files.walk(temporary.getRoot().toPath())) {
            files.filter(Files::isRegularFile).forEach(path -> path.toFile().setWritable(true));
        }
    }
}
