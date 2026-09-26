package example.npatch.smoke;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import java.security.MessageDigest;
import java.util.Arrays;
import org.json.JSONObject;

final class SignatureProbe {
    private static final int FLAGS = PackageManager.GET_SIGNATURES | PackageManager.GET_SIGNING_CERTIFICATES;

    // Invalid certificate flags below are intentional negative test inputs.
    @android.annotation.SuppressLint("WrongConstant")
    static boolean check(Context context) {
        try {
            PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            String installedPath = installed.applicationInfo.sourceDir;
            String metadata = installed.applicationInfo.metaData == null ? null : installed.applicationInfo.metaData.getString("npatch");
            boolean wrapped = metadata != null;
            boolean enabled = wrapped && new JSONObject(new String(android.util.Base64.decode(metadata, android.util.Base64.DEFAULT),
                    java.nio.charset.StandardCharsets.UTF_8)).optInt("sigBypassLevel") > 0;
            boolean expected = !wrapped || enabled;
            PackageManager manager = context.getPackageManager();
            PackageInfo original = manager.getPackageArchiveInfo(context.getApplicationInfo().sourceDir, FLAGS);
            Signature[] originalSigners = original.signingInfo.getApkContentsSigners();
            byte[] certificate = originalSigners[0].toByteArray();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate);
            PackageInfo current = manager.getPackageInfo(context.getPackageName(), FLAGS);
            boolean legacy = Arrays.equals(original.signatures, current.signatures) == expected;
            boolean modern = Arrays.equals(originalSigners, current.signingInfo.getApkContentsSigners()) == expected;
            boolean history = Arrays.equals(original.signingInfo.getSigningCertificateHistory(),
                    current.signingInfo.getSigningCertificateHistory()) == expected;
            boolean flagsApi = Build.VERSION.SDK_INT < 33 || (Arrays.equals(originalSigners,
                    manager.getPackageInfo(context.getPackageName(), PackageManager.PackageInfoFlags.of(FLAGS))
                            .signingInfo.getApkContentsSigners()) == expected);
            boolean packageRaw = manager.hasSigningCertificate(context.getPackageName(), certificate, 0);
            boolean uidRaw = manager.hasSigningCertificate(Process.myUid(), certificate, 0);
            boolean packageDigest = manager.hasSigningCertificate(context.getPackageName(), digest, 1);
            boolean uidDigest = manager.hasSigningCertificate(Process.myUid(), digest, 1);
            boolean invalid = manager.hasSigningCertificate(context.getPackageName(), new byte[] {99}, 0);
            boolean certificateQueries = packageRaw == expected && uidRaw == expected
                    && packageDigest == expected && uidDigest == expected && !invalid;
            PackageInfo archive = manager.getPackageArchiveInfo(installedPath, FLAGS);
            boolean archiveQuery = Arrays.equals(originalSigners, archive.signingInfo.getApkContentsSigners()) == expected;
            byte[] platform = manager.getPackageInfo("android", FLAGS).signingInfo.getApkContentsSigners()[0].toByteArray();
            boolean unrelated = !manager.hasSigningCertificate("android", certificate, 0)
                    && manager.hasSigningCertificate("android", platform, 0);
            boolean identity = current.packageName.equals(context.getPackageName()) && current.applicationInfo.uid == Process.myUid();
            Signature[] expectedSigners = current.signingInfo.getApkContentsSigners().clone();
            if (enabled) current.signingInfo.getApkContentsSigners()[0] = new Signature(new byte[] {88});
            boolean copies = true;
            for (int i = 0; i < 20; i++) {
                copies &= Arrays.equals(expectedSigners, manager.getPackageInfo(context.getPackageName(), FLAGS)
                        .signingInfo.getApkContentsSigners());
            }
            boolean pass = legacy && modern && history && flagsApi && certificateQueries && archiveQuery && unrelated && identity && copies;
            Log.i("WrapperSmoke", "SIGNATURE " + (pass ? "PASS" : "FAIL") + " enabled=" + enabled
                    + " pid=" + Process.myPid() + " legacy=" + legacy + " modern=" + modern + " history=" + history
                    + " flagsApi=" + flagsApi + " certificateQueries=" + certificateQueries + " archive=" + archiveQuery
                    + " unrelated=" + unrelated + " identity=" + identity + " copies=" + copies);
            Log.i("WrapperSmoke", "CERTIFICATE_QUERIES expected=" + expected
                    + " packageRaw=" + packageRaw + " uidRaw=" + uidRaw
                    + " packageDigest=" + packageDigest + " uidDigest=" + uidDigest
                    + " invalid=" + invalid);
            return pass;
        } catch (Throwable error) { Log.e("WrapperSmoke", "SIGNATURE FAIL", error); return false; }
    }
}
