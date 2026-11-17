package net.vash.ft8cbdroid.validation;

import android.content.*;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.io.*;

/** Separate-UID test documents, including stalled pipes and denied destinations. */
public final class LogDocumentProvider extends ContentProvider {
    private ParcelFileDescriptor held;
    @Override public boolean onCreate() { return true; }
    private void authorize() {
        try {
            ApplicationInfo app = getContext().getPackageManager().getApplicationInfo("net.vash.ft8cbdroid", 0);
            if ((app.flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0 || Binder.getCallingUid() != app.uid) throw new SecurityException("Debug target only");
        } catch (Exception error) { throw new SecurityException(error); }
    }
    @Override public synchronized ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        authorize(); String name = uri.getLastPathSegment();
        if ("fail".equals(name)) throw new FileNotFoundException("Intentional log-document failure");
        if ("stall".equals(name)) {
            try { if (held != null) held.close(); ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe(); held = pipe[1]; return pipe[0]; }
            catch (IOException e) { throw new FileNotFoundException(e.toString()); }
        }
        if (name == null || !name.matches("[a-zA-Z0-9-]+")) throw new FileNotFoundException("Invalid fixture name");
        File file = new File(getContext().getFilesDir(), "log-" + name);
        return ParcelFileDescriptor.open(file, "r".equals(mode) ? ParcelFileDescriptor.MODE_READ_ONLY :
            ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
    }
    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        authorize(); Bundle result = new Bundle();
        if ("shareResult".equals(method)) {
            File file = new File(getContext().getFilesDir(), "log-share-result");
            try { result.putString("result", new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8)); }
            catch (IOException e) { result.putString("result", "pending"); }
        } else if ("cleanup".equals(method)) {
            try { if (held != null) held.close(); } catch (IOException ignored) {} held = null;
            for (File file : getContext().getFilesDir().listFiles()) if (file.getName().startsWith("log-")) {
                if (file.isDirectory()) { for (File child : file.listFiles()) if (!child.getName().equals("desktop.adi")) child.delete(); }
                else file.delete();
            }
        }
        return result;
    }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
