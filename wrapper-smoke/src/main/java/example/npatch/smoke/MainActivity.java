package example.npatch.smoke;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final String ORIGINAL_PACKAGE = "example.npatch.smoke";
    private static boolean applicationReady, providerReady, serviceReady, receiverReady, factoryReady, viewReady;
    private static boolean codePathReady;
    private static boolean signatureReady;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.smoke);
        var preferences = getSharedPreferences("smoke", MODE_PRIVATE);
        int count = preferences.getInt("launches", 0) + 1;
        preferences.edit().putInt("launches", count).apply();
        startService(new Intent(this, Service.class));
        startService(new Intent(this, RemoteService.class));
        sendBroadcast(new Intent(this, Receiver.class));
        findViewById(R.id.second).setOnClickListener(v -> startActivity(new Intent(this, Second.class)));
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try (Cursor cursor = getContentResolver().query(Uri.parse("content://" + getPackageName() + ".probe"), null, null, null, null)) {
                String currentPackage = getPackageName();
                boolean packageIdentity = ORIGINAL_PACKAGE.equals(currentPackage);
                boolean query = cursor != null && cursor.moveToFirst() && currentPackage.equals(cursor.getString(0));
                ClassLoader contextLoader = getClassLoader();
                ClassLoader activityLoader = MainActivity.class.getClassLoader();
                boolean sameClassLoader = contextLoader == activityLoader;
                boolean classLoaderPath = activityLoader.toString().contains(getApplicationInfo().sourceDir);
                boolean classLoader = sameClassLoader;
                boolean resource = "Loader Smoke Test".equals(getString(R.string.app_name));
                boolean namedResource = getResources().getIdentifier("smoke", "layout", getPackageName()) == R.layout.smoke;
                boolean originalNamedResource = getResources().getIdentifier(
                        "smoke", "layout", ORIGINAL_PACKAGE) == R.layout.smoke;
                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(0, 0);
                path.lineTo(10, 10);
                System.loadLibrary("androidx.graphics.path");
                androidx.graphics.path.PathIterator iterator = new androidx.graphics.path.PathIterator(path,
                        androidx.graphics.path.PathIterator.ConicEvaluation.AsConic, 0.25f);
                boolean nativeLibrary = iterator.hasNext() && iterator.calculateSize(false) > 0;
                boolean pass = applicationReady && providerReady && serviceReady && receiverReady && factoryReady && viewReady && query && classLoader && resource && namedResource && originalNamedResource && nativeLibrary && codePathReady && signatureReady;
                String result = (pass ? "PASS" : "FAIL") + "\npackage=" + currentPackage
                        + "\nexpectedPackage=" + ORIGINAL_PACKAGE + " packageIdentity=" + packageIdentity
                        + "\nlaunches=" + count
                        + "\napplication=" + applicationReady + " provider=" + providerReady
                        + "\nservice=" + serviceReady + " receiver=" + receiverReady
                        + "\nfactory=" + factoryReady + " customView=" + viewReady
                        + "\nproviderQuery=" + query + " classLoader=" + classLoader + " resources=" + resource
                        + "\nnativeLibrary=" + nativeLibrary + " codePath=" + codePathReady + " namedResource=" + namedResource
                        + " originalNamedResource=" + originalNamedResource
                        + " signatures=" + signatureReady;
                ((TextView) findViewById(R.id.result)).setText(result);
                Log.i("WrapperSmoke", result.replace('\n', ' '));
                Log.i("WrapperSmoke", "PACKAGE_IDENTITY " + (packageIdentity ? "PASS" : "FAIL")
                        + " current=" + currentPackage + " expected=" + ORIGINAL_PACKAGE);
                Log.i("WrapperSmoke", "CLASSLOADER same=" + sameClassLoader
                        + " path=" + classLoaderPath + " source=" + getApplicationInfo().sourceDir
                        + " context=" + contextLoader + " activity=" + activityLoader);
            } catch (Throwable error) {
                Log.e("WrapperSmoke", "FAIL", error);
                ((TextView) findViewById(R.id.result)).setText("FAIL: " + error);
            }
        }, 750);
    }

    public static class Factory extends AppComponentFactory {
        @Override public Application instantiateApplication(ClassLoader loader, String name)
                throws InstantiationException, IllegalAccessException, ClassNotFoundException {
            factoryReady = true;
            return super.instantiateApplication(loader, name);
        }
    }
    public static class App extends Application {
        @Override protected void attachBaseContext(Context base) {
            super.attachBaseContext(base);
            signatureReady = SignatureProbe.check(base);
            try (java.util.zip.ZipFile apk = new java.util.zip.ZipFile(base.getApplicationInfo().sourceDir);
                 java.util.zip.ZipFile resourceApk = new java.util.zip.ZipFile(base.getPackageResourcePath())) {
                String sourceDir = base.getApplicationInfo().sourceDir;
                String publicSourceDir = base.getApplicationInfo().publicSourceDir;
                String codePath = base.getPackageCodePath();
                String resourcePath = base.getPackageResourcePath();
                boolean sourceIsOriginal = apk.getEntry("assets/base.apk") == null;
                boolean sourceMatchesCode = sourceDir.equals(codePath);
                boolean codeMatchesResources = codePath.equals(resourcePath);
                boolean publicMatchesSource = publicSourceDir.equals(sourceDir);
                boolean resourceIsWrapper = resourceApk.getEntry("assets/base.apk") != null;
                boolean renamed = !ORIGINAL_PACKAGE.equals(base.getPackageName());
                boolean dexMatches = apk.getEntry("classes.dex").getCrc()
                        == resourceApk.getEntry("classes.dex").getCrc();
                boolean resourcePathReady = renamed
                        ? resourceIsWrapper && !codeMatchesResources
                        : codeMatchesResources && !resourceIsWrapper;
                codePathReady = sourceIsOriginal && publicMatchesSource
                        && resourcePathReady && dexMatches;
                Log.i("WrapperSmoke", "CODE_PATH sourceIsOriginal=" + sourceIsOriginal
                        + " sourceMatchesCode=" + sourceMatchesCode
                        + " codeMatchesResources=" + codeMatchesResources
                        + " resourceIsWrapper=" + resourceIsWrapper
                        + " publicMatchesSource=" + publicMatchesSource
                        + " dexMatches=" + dexMatches + " source=" + sourceDir
                        + " public=" + publicSourceDir + " code=" + codePath
                        + " resources=" + resourcePath);
            } catch (java.io.IOException error) {
                Log.e("WrapperSmoke", "Cannot read application code path", error);
            }
        }
        @Override public void onCreate() { super.onCreate(); applicationReady = true; }
    }
    public static class ProbeView extends LinearLayout {
        public ProbeView(Context context, AttributeSet attributes) { super(context, attributes); viewReady = true; }
    }
    public static class Service extends android.app.Service {
        @Override public void onCreate() { super.onCreate(); serviceReady = true; stopSelf(); }
        @Override public IBinder onBind(Intent intent) { return null; }
    }
    public static class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) { receiverReady = true; }
    }
    public static class RemoteService extends android.app.Service {
        @Override public void onCreate() {
            super.onCreate();
            Log.i("WrapperSmoke", "REMOTE SIGNATURE " + (signatureReady ? "PASS" : "FAIL"));
            stopSelf();
        }
        @Override public IBinder onBind(Intent intent) { return null; }
    }
    public static class Provider extends ContentProvider {
        @Override public boolean onCreate() { providerReady = true; return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            MatrixCursor cursor = new MatrixCursor(new String[] {"package"});
            cursor.addRow(new Object[] {getContext().getPackageName()});
            return cursor;
        }
        @Override public String getType(Uri uri) { return "text/plain"; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }
    public static class Second extends Activity {
        @Override public void onCreate(Bundle state) {
            super.onCreate(state);
            TextView text = new TextView(this);
            text.setPadding(50, 100, 50, 50);
            text.setText("SECOND ACTIVITY PASS\n" + getPackageName());
            setContentView(text);
            Log.i("WrapperSmoke", "SECOND ACTIVITY PASS " + getPackageName());
        }
    }
}
