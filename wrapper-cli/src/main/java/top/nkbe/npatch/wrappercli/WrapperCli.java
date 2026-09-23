package top.nkbe.npatch.wrappercli;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import top.nkbe.npatch.patch.wrapper.WrapperManifest;
import top.nkbe.npatch.patch.wrapper.WrapperPacker;
import top.nkbe.npatch.patch.wrapper.WrapperSigning;

public final class WrapperCli {
    @Parameter(description = "input.apk", required = true) private List<String> input = new ArrayList<>();
    @Parameter(names = {"-o", "--output"}, required = true, description = "Output directory (input filename is preserved)") private String directory;
    @Parameter(names = {"-p", "--package"}, description = "Optional package override (default: preserve original)") private String target;
    @Parameter(names = "--keystore", description = "Custom signing keystore") private String keystore;
    @Parameter(names = "--store-type") private String type = "BKS";
    @Parameter(names = "--alias") private String alias;
    @Parameter(names = "--store-password-env", description = "Environment variable containing the store password") private String passwordEnv;
    @Parameter(names = "--key-password-env") private String keyPasswordEnv;
    @Parameter(names = "--signature-compat", description = "Enable NPatch signature compatibility") private boolean signatureCompat;
    @Parameter(names = {"-h", "--help"}, help = true) private boolean help;

    public static void main(String[] args) {
        WrapperCli command = new WrapperCli();
        JCommander parser = JCommander.newBuilder().addObject(command).programName("apk-wrapper").build();
        try {
            parser.parse(args);
            if (command.help) { parser.usage(); return; }
            command.run();
        } catch (Exception error) {
            System.err.println(error.getMessage());
            System.exit(1);
        }
    }

    private void run() throws Exception {
        if (input.size() != 1) throw new IllegalArgumentException("Exactly one standalone APK is required; splits are not supported");
        File source = new File(input.get(0)).getCanonicalFile();
        WrapperManifest manifest = WrapperPacker.inspect(source);
        String packageName = target == null ? manifest.packageName : target;
        KeyStore.PrivateKeyEntry signer;
        if (keystore == null) {
            try (InputStream key = getClass().getResourceAsStream("/assets/npatch.key")) { signer = WrapperSigning.builtin(key); }
        } else {
            char[] password = env(passwordEnv);
            char[] keyPassword = keyPasswordEnv == null ? password : env(keyPasswordEnv);
            try (InputStream key = new FileInputStream(keystore)) {
                signer = WrapperSigning.load(key, type, password, alias, keyPassword);
            } finally {
                java.util.Arrays.fill(password, '\0');
                java.util.Arrays.fill(keyPassword, '\0');
            }
        }
        try (InputStream loader = getClass().getResourceAsStream("/assets/wrapper/loader.dex")) {
            if (loader == null) throw new IllegalStateException("Standalone loader is missing from this JAR");
            byte[] runtime;
            try (InputStream input = getClass().getResourceAsStream("/assets/wrapper/runtime.zip")) {
                if (input == null) throw new IllegalStateException("NPatch runtime is missing from this JAR");
                runtime = input.readAllBytes();
            }
            WrapperPacker.pack(source, new File(directory, source.getName()), packageName,
                    loader.readAllBytes(), signer, runtime, signatureCompat, System.out::println);
        }
    }

    private char[] env(String name) {
        String value = name == null ? null : System.getenv(name);
        if (value == null) throw new IllegalArgumentException("Signing password environment variable is not set");
        return value.toCharArray();
    }
}
