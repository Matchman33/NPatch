package top.nkbe.npatch.loader;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SigBypassCallerTest {
    @Test public void hookInfrastructureDoesNotMakeApplicationCallerAModule() {
        assertFalse(SigBypass.isModuleCaller(stack(
                "java.lang.Thread",
                "top.nkbe.npatch.loader.SigBypass",
                "top.nkbe.npatch.loader.SigBypass$9",
                "de.robv.android.xposed.XposedBridge",
                "org.matrix.vector.legacy.LegacyDelegateImpl",
                "org.matrix.vector.nativebridge.HookBridge",
                "org.lsposed.lspd.nativebridge.SigBypass",
                "LSPHooker_",
                "android.app.ApplicationPackageManager",
                "example.target.SignatureCheck"
        )));
    }

    @Test public void realModuleFrameIsStillDetectedAfterHookInfrastructure() {
        assertTrue(SigBypass.isModuleCaller(stack(
                "java.lang.Thread",
                "top.nkbe.npatch.loader.SigBypass$9",
                "de.robv.android.xposed.XposedBridge$Hooker",
                "android.app.ApplicationPackageManager",
                "io.github.libxposed.module.ExampleModule"
        )));
    }

    @Test public void sensitiveFrameworkFrameAloneIsNotAModule() {
        assertFalse(SigBypass.isModuleCaller(stack(
                "java.lang.Thread",
                "top.nkbe.npatch.loader.SigBypass$9",
                "android.content.pm.parsing.PackageInfoUtils",
                "example.target.PackageReader"
        )));
    }

    @Test public void samePackageStandaloneCanExposeOriginalApkPath() {
        assertEquals("/data/cache/original.apk", SigBypass.selectApplicationApkPath(
                "/data/cache/original.apk", "/data/app/base.apk", true));
        assertEquals("/data/app/base.apk", SigBypass.selectApplicationApkPath(
                "/data/cache/original.apk", "/data/app/base.apk", false));
    }

    private static StackTraceElement[] stack(String... classNames) {
        StackTraceElement[] stack = new StackTraceElement[classNames.length];
        for (int i = 0; i < classNames.length; i++) {
            stack[i] = new StackTraceElement(classNames[i], "call", "Source.java", i + 1);
        }
        return stack;
    }
}
