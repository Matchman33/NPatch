import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

/** Local I/O comparison; this is not an Android startup benchmark. */
class OriginCopyBenchmark {
    public static void main(String[] args) throws Exception {
        int mib = args.length == 0 ? 128 : Integer.parseInt(args[0]);
        if (mib < 1 || mib > 512) throw new IllegalArgumentException("Use 1..512 MiB");
        Files.createDirectories(Path.of("out"));
        Path directory = Files.createTempDirectory(Path.of("out"), "origin-copy-bench-");
        Path source = directory.resolve("source.bin");
        Path target = directory.resolve("target.bin");
        byte[] buffer = new byte[65536];
        new Random(42).nextBytes(buffer);
        try {
            try (var output = Files.newOutputStream(source)) {
                for (int i = 0; i < mib * 16; i++) output.write(buffer);
            }
            for (int round = 0; round < 4; round++) {
                long[] elapsed = new long[2];
                byte[][] hashes = new byte[2][];
                for (int order = 0; order < 2; order++) {
                    int mode = (round + order) % 2;
                    long start = System.nanoTime();
                    var digest = MessageDigest.getInstance("SHA-256");
                    try (InputStream input = Files.newInputStream(source);
                         FileOutputStream output = new FileOutputStream(target.toFile())) {
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                            if (mode == 1) digest.update(buffer, 0, count);
                        }
                        output.getFD().sync();
                    }
                    if (mode == 0) {
                        try (InputStream input = Files.newInputStream(target)) {
                            int count;
                            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
                        }
                    }
                    hashes[mode] = digest.digest();
                    elapsed[mode] = (System.nanoTime() - start) / 1_000_000;
                }
                if (!Arrays.equals(hashes[0], hashes[1])) throw new AssertionError("Digest mismatch");
                System.out.printf("round=%d sizeMiB=%d separateReadMs=%d streamingMs=%d digestEqual=true%n",
                        round, mib, elapsed[0], elapsed[1]);
            }
            System.out.printf("separateReadMiB=%d streamingReadMiB=%d%n", mib * 2, mib);
        } finally {
            Files.deleteIfExists(target);
            Files.deleteIfExists(source);
            Files.delete(directory);
        }
    }
}
