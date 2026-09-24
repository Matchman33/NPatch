package top.nkbe.npatch.loader;

import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.Log;

import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

final class ResourcePackageCompat {
    private static final String TAG = "NPatch";
    private static boolean installed;
    private static String installedOriginalPackage;
    private static String installedTargetPackage;

    private ResourcePackageCompat() {}

    static synchronized void install(String originalPackage, String targetPackage) {
        if (originalPackage == null || targetPackage == null || originalPackage.equals(targetPackage)) return;
        if (installed) {
            if (originalPackage.equals(installedOriginalPackage)
                    && targetPackage.equals(installedTargetPackage)) return;
            throw new IllegalStateException("Resource package compatibility is already installed");
        }

        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length < 3) return;
                if (param.args[0] instanceof String name) {
                    param.args[0] = mapResourceName(name, originalPackage, targetPackage);
                }
                if (param.args[2] instanceof String defaultPackage) {
                    param.args[2] = mapDefaultPackage(defaultPackage, originalPackage, targetPackage);
                }
            }
        };

        boolean hooked = hookAllMethods(Resources.class, "getIdentifier", hook);
        hooked |= hookAllMethods(AssetManager.class, "getResourceIdentifier", hook);
        if (!hooked) throw new IllegalStateException("Cannot hook dynamic resource lookup");
        installedOriginalPackage = originalPackage;
        installedTargetPackage = targetPackage;
        installed = true;
        Log.i(TAG, "Resource package compatibility installed: "
                + originalPackage + " -> " + targetPackage);
    }

    static String mapDefaultPackage(String value, String originalPackage, String targetPackage) {
        return originalPackage.equals(value) ? targetPackage : value;
    }

    static String mapResourceName(String value, String originalPackage, String targetPackage) {
        if (value == null) return null;
        String prefix = originalPackage + ":";
        return value.startsWith(prefix) ? targetPackage + value.substring(originalPackage.length()) : value;
    }

    private static boolean hookAllMethods(Class<?> type, String methodName, XC_MethodHook hook) {
        try {
            Set<?> hooks = XposedBridge.hookAllMethods(type, methodName, hook);
            return hooks != null && !hooks.isEmpty();
        } catch (Throwable error) {
            Log.w(TAG, "Cannot hook " + type.getName() + "." + methodName, error);
            return false;
        }
    }
}
