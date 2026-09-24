package top.nkbe.npatch.share;

public final class WrapperConfig {
    public static final String GADGET_PREFIX = "gadget/";
    public static final String GADGET_LIBRARY = "libnpatch-gadget.so";
    public static final String GADGET_CONFIG = "libnpatch-gadget.config.so";
    public static final String GADGET_SCRIPT = "libscript.so";
    public static final int FORMAT_VERSION = 1;
    public static final String APK_PATH = "assets/base.apk";
    public static final String CONFIG_PATH = "assets/wrapper/config.json";
    public static final String DEX_PATH = "assets/wrapper/loader.dex";
    public static final String RUNTIME_PREFIX = "assets/npatch/";
    public static final String HOOK_RUNTIME = "npatch-vector-lsplant";
    public static final String FACTORY = Constants.PROXY_APP_COMPONENT_FACTORY;
    public static final String MARKER = "npatch.wrapper";
    public static final String ORIGINAL_PACKAGE_MARKER = "npatch.wrapper.original";

    public int formatVersion = FORMAT_VERSION;
    public String originalPackage;
    public String wrapperPackage;
    public String appComponentFactory;
    public String apkSha256;
    public boolean signatureCompat;
    public String hookRuntime;
    public boolean gadgetEnabled;
    public String gadgetAbi;
    public String gadgetMode;
    public java.util.Map<String, String> runtimeSha256;
}
