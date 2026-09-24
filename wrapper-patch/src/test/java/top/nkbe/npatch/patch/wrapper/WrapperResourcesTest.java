package top.nkbe.npatch.patch.wrapper;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class WrapperResourcesTest {
    @Test public void rewritesMatchingPackageNameWithoutChangingResourceTableLayout() throws Exception {
        byte[] original = resourceTable("example.original", 0x7f);
        byte[] rewritten = WrapperResources.rewrite(
                original,
                "example.original",
                "example.renamed"
        );

        assertEquals(original.length, rewritten.length);
        assertEquals("example.original", packageName(original));
        assertEquals("example.renamed", packageName(rewritten));
        assertEquals(0x7f, packageId(rewritten));

        byte[] originalWithoutName = original.clone();
        byte[] rewrittenWithoutName = rewritten.clone();
        java.util.Arrays.fill(originalWithoutName, 24, 24 + 256, (byte) 0);
        java.util.Arrays.fill(rewrittenWithoutName, 24, 24 + 256, (byte) 0);
        assertArrayEquals(originalWithoutName, rewrittenWithoutName);
    }

    @Test public void rejectsMissingPackageAndMalformedTable() {
        assertThrows(IOException.class, () -> WrapperResources.rewrite(
                resourceTable("example.other", 0x7f), "example.original", "example.renamed"));

        byte[] malformed = resourceTable("example.original", 0x7f);
        ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 0);
        assertThrows(IOException.class, () -> WrapperResources.rewrite(
                malformed, "example.original", "example.renamed"));
    }

    @Test public void rejectsPackageNameThatDoesNotFitFixedResourceField() {
        String target = "a".repeat(128);
        assertThrows(IOException.class, () -> WrapperResources.rewrite(
                resourceTable("example.original", 0x7f), "example.original", target));
    }

    @Test public void rewritesOnlyMatchingPackageInMultiPackageTable() throws Exception {
        byte[] original = resourceTable(
                new String[] {"example.shared", "example.original"},
                new int[] {0x02, 0x7f});
        byte[] rewritten = WrapperResources.rewrite(
                original, "example.original", "example.renamed");

        assertEquals("example.shared", packageName(rewritten, 0));
        assertEquals("example.renamed", packageName(rewritten, 1));
    }

    private static byte[] resourceTable(String packageName, int packageId) {
        return resourceTable(new String[] {packageName}, new int[] {packageId});
    }

    private static byte[] resourceTable(String[] packageNames, int[] packageIds) {
        int tableSize = 12 + packageNames.length * 288;
        ByteBuffer buffer = ByteBuffer.allocate(tableSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) 0x0002).putShort((short) 12).putInt(tableSize).putInt(packageNames.length);
        for (int i = 0; i < packageNames.length; i++) {
            int packageStart = buffer.position();
            buffer.putShort((short) 0x0200).putShort((short) 288).putInt(288).putInt(packageIds[i]);
            buffer.put(packageNames[i].getBytes(StandardCharsets.UTF_16LE));
            buffer.position(packageStart + 288);
        }
        return buffer.array();
    }

    private static int packageId(byte[] table) {
        return ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN).getInt(20);
    }

    private static String packageName(byte[] table) {
        return packageName(table, 0);
    }

    private static String packageName(byte[] table, int packageIndex) {
        int start = 24 + packageIndex * 288;
        int end = start;
        while (end + 1 < start + 256 && (table[end] != 0 || table[end + 1] != 0)) {
            end += 2;
        }
        return new String(table, start, end - start, StandardCharsets.UTF_16LE);
    }
}
