package net.vash.ft8cbdroid.validation;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only provider. Java keeps its standalone process independent of the target's Kotlin runtime. */
public final class WaveformTestProvider extends ContentProvider {
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger writes = new AtomicInteger();

    @Override public boolean onCreate() { return true; }

    private void authorize() {
        try {
            ApplicationInfo app = getContext().getPackageManager().getApplicationInfo("net.vash.ft8cbdroid", 0);
            if ((app.flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0 || Binder.getCallingUid() != app.uid) {
                throw new SecurityException("Only the debug target app can access this test provider");
            }
        } catch (PackageManager.NameNotFoundException error) {
            throw new SecurityException("Debug target app is not installed", error);
        }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        authorize();
        if ("/fail".equals(uri.getPath())) {
            failures.incrementAndGet();
            throw new FileNotFoundException("Intentional R07 write failure");
        }
        if (!"/success".equals(uri.getPath())) throw new FileNotFoundException("Unknown test document");
        File file = new File(getContext().getFilesDir(), "r07-test.wav");
        if ("r".equals(mode)) return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        writes.incrementAndGet();
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY |
            ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        authorize();
        Bundle result = new Bundle();
        result.putInt("failures", failures.get());
        result.putInt("writes", writes.get());
        return result;
    }
    @Override public String getType(Uri uri) { return "audio/wav"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
