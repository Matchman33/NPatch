package top.nkbe.npatch.patch.wrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class WrapperResources {
    private static final int RES_TABLE_TYPE = 0x0002;
    private static final int RES_TABLE_PACKAGE_TYPE = 0x0200;
    private static final int CHUNK_HEADER_SIZE = 8;
    private static final int TABLE_HEADER_SIZE = 12;
    private static final int PACKAGE_NAME_OFFSET = 12;
    private static final int PACKAGE_NAME_SIZE = 256;

    private WrapperResources() {}

    static byte[] rewrite(byte[] table, String originalPackage, String targetPackage) throws IOException {
        if (table == null) throw new IOException("Missing resources.arsc");
        if (originalPackage == null || originalPackage.isEmpty()) throw new IOException("Missing original package name");
        if (targetPackage == null || targetPackage.isEmpty()) throw new IOException("Missing target package name");
        byte[] targetName = targetPackage.getBytes(StandardCharsets.UTF_16LE);
        if (targetName.length > PACKAGE_NAME_SIZE - 2) {
            throw new IOException("Target package name is too long for resources.arsc: " + targetPackage);
        }
        if (table.length < TABLE_HEADER_SIZE || u16(table, 0) != RES_TABLE_TYPE) {
            throw new IOException("Invalid resources.arsc table header");
        }
        int tableHeaderSize = u16(table, 2);
        long tableSize = u32(table, 4);
        if (tableHeaderSize < TABLE_HEADER_SIZE || tableHeaderSize > table.length || tableSize != table.length) {
            throw new IOException("Invalid resources.arsc table bounds");
        }

        byte[] rewritten = table.clone();
        int matches = 0;
        int packageChunks = 0;
        int offset = tableHeaderSize;
        while (offset < table.length) {
            if (table.length - offset < CHUNK_HEADER_SIZE) throw new IOException("Truncated resources.arsc chunk header");
            int type = u16(table, offset);
            int headerSize = u16(table, offset + 2);
            long chunkSizeLong = u32(table, offset + 4);
            if (headerSize < CHUNK_HEADER_SIZE || chunkSizeLong < headerSize
                    || chunkSizeLong > table.length - offset) {
                throw new IOException("Invalid resources.arsc chunk bounds at " + offset);
            }
            int chunkSize = (int) chunkSizeLong;
            if (type == RES_TABLE_PACKAGE_TYPE) {
                packageChunks++;
                if (headerSize < PACKAGE_NAME_OFFSET + PACKAGE_NAME_SIZE) {
                    throw new IOException("Invalid resources.arsc package header at " + offset);
                }
                String packageName = readPackageName(table, offset + PACKAGE_NAME_OFFSET);
                if (originalPackage.equals(packageName)) {
                    matches++;
                    int nameOffset = offset + PACKAGE_NAME_OFFSET;
                    Arrays.fill(rewritten, nameOffset, nameOffset + PACKAGE_NAME_SIZE, (byte) 0);
                    System.arraycopy(targetName, 0, rewritten, nameOffset, targetName.length);
                }
            }
            offset += chunkSize;
        }
        if (offset != table.length) throw new IOException("Invalid resources.arsc trailing data");
        long declaredPackages = u32(table, 8);
        if (declaredPackages != packageChunks) throw new IOException("resources.arsc package count mismatch");
        if (matches == 0) throw new IOException("Original package is missing from resources.arsc: " + originalPackage);
        if (matches > 1) throw new IOException("Original package is duplicated in resources.arsc: " + originalPackage);
        return rewritten;
    }

    private static String readPackageName(byte[] table, int offset) throws IOException {
        int end = offset;
        int limit = offset + PACKAGE_NAME_SIZE;
        while (end + 1 < limit) {
            if (table[end] == 0 && table[end + 1] == 0) {
                return new String(table, offset, end - offset, StandardCharsets.UTF_16LE);
            }
            end += 2;
        }
        throw new IOException("Unterminated package name in resources.arsc");
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static long u32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL)
                | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16)
                | ((bytes[offset + 3] & 0xffL) << 24);
    }
}
