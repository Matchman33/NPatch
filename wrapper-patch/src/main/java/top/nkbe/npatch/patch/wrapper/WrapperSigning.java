package top.nkbe.npatch.patch.wrapper;

import java.io.InputStream;
import java.security.KeyStore;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

public final class WrapperSigning {
    private WrapperSigning() {}

    public static KeyStore.PrivateKeyEntry load(InputStream input, String type, char[] password,
                                                String alias, char[] keyPassword) throws Exception {
        KeyStore store = type.equalsIgnoreCase("BKS")
                ? KeyStore.getInstance(type, new BouncyCastleProvider()) : KeyStore.getInstance(type);
        store.load(input, password);
        KeyStore.Entry entry = store.getEntry(alias, new KeyStore.PasswordProtection(keyPassword));
        if (!(entry instanceof KeyStore.PrivateKeyEntry)) throw new IllegalArgumentException("Signing alias has no private key");
        return (KeyStore.PrivateKeyEntry) entry;
    }

    public static KeyStore.PrivateKeyEntry builtin(InputStream input) throws Exception {
        if (input == null) throw new IllegalArgumentException("Built-in signing key is missing");
        return load(input, "BKS", "123456".toCharArray(), "key0", "123456".toCharArray());
    }
}
