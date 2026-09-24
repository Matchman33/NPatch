package top.nkbe.npatch.wrappercli;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import top.nkbe.npatch.patch.wrapper.WrapperManifest;
import top.nkbe.npatch.patch.wrapper.WrapperGadget;
import top.nkbe.npatch.patch.wrapper.WrapperPacker;
import top.nkbe.npatch.patch.wrapper.WrapperSigning;

public final class WrapperCli {
    private static final long MAX_GADGET_SIZE = 128L * 1024 * 1024;
    private static final long MAX_SCRIPT_SIZE = 16L * 1024 * 1024;
    @Parameter(description = "input.apk", required = true) private List<String> input = new ArrayList<>();
    @Parameter(names = {"-o", "--output"}, required = true, description = "Output directory (input filename is preserved)") private String directory;
    @Parameter(names = {"-p", "--package"}, description = "Optional package override (default: preserve original)") private String target;
    @Parameter(names = "--keystore", description = "Custom signing keystore") private String keystore;
    @Parameter(names = "--store-type") private String type = "BKS";
    @Parameter(names = "--alias") private String alias;
    @Parameter(names = "--store-password-env", description = "Environment variable containing the store password") private String passwordEnv;
    @Parameter(names = "--key-password-env") private String keyPasswordEnv;
    @Parameter(names = "--signature-compat", description = "Enable NPatch signature compatibility") private boolean signatureCompat;
    @Parameter(names = "--gadget", description = "Local Frida Gadget ELF file") private String gadget;
    @Parameter(names = "--gadget-mode", description = "Frida Gadget mode: listen or script") private String gadgetMode = "listen";
    @Parameter(names = "--gadget-address", description = "Listen address") private String gadgetAddress = "127.0.0.1";
    @Parameter(names = "--gadget-port", description = "Listen port") private int gadgetPort = 27043;
    @Parameter(names = "--gadget-resume", description = "Do not wait for a client when loading Listen mode") private boolean gadgetResume;
    @Parameter(names = "--gadget-script", description = "UTF-8 JavaScript file for Script mode") private String gadgetScript;
    @Parameter(names = {"-h", "--help"}, help = true) private boolean help;

    public static void main(String[] args) {
        WrapperCli command = new WrapperCli();
        JCommander parser = JCommander.newBuilder().addObject(command).programName("apkloom").build();
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
            WrapperGadget selectedGadget = gadget();
            WrapperPacker.pack(source, new File(directory, source.getName()), packageName,
                    loader.readAllBytes(), signer, runtime, signatureCompat, selectedGadget, System.out::println);
        }
    }

    private WrapperGadget gadget() throws Exception {
        if (gadget == null) {
            if (gadgetScript != null) throw new IllegalArgumentException("--gadget-script requires --gadget");
            return null;
        }
        byte[] library = readFile(new File(gadget), MAX_GADGET_SIZE, "Frida Gadget");
        if ("listen".equalsIgnoreCase(gadgetMode)) {
            if (gadgetScript != null) throw new IllegalArgumentException("--gadget-script is only valid in script mode");
            return WrapperGadget.listen(library, gadgetAddress, gadgetPort, !gadgetResume);
        }
        if ("script".equalsIgnoreCase(gadgetMode)) {
            if (gadgetScript == null) throw new IllegalArgumentException("Script mode requires --gadget-script");
            return WrapperGadget.script(library, readFile(new File(gadgetScript), MAX_SCRIPT_SIZE, "Gadget script"));
        }
        throw new IllegalArgumentException("--gadget-mode must be listen or script");
    }

    private byte[] readFile(File file, long maximumSize, String label) throws Exception {
        if (!file.isFile()) throw new IllegalArgumentException(label + " file not found: " + file);
        long size = Files.size(file.toPath());
        if (size == 0 || size > maximumSize) throw new IllegalArgumentException(label + " file has an invalid size");
        return Files.readAllBytes(file.toPath());
    }

    private char[] env(String name) {
        String value = name == null ? null : System.getenv(name);
        if (value == null) throw new IllegalArgumentException("Signing password environment variable is not set");
        return value.toCharArray();
    }
}
