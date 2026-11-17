package net.vash.ft8cbdroid.validation;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.*;

/** Test-only SAF root; contains only owned fixtures and exports, never user documents. */
public final class LogSafProvider extends DocumentsProvider {
    private static final String[] ROOTS = { DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.COLUMN_MIME_TYPES, DocumentsContract.Root.COLUMN_ICON };
    private static final String[] DOCS = { DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED };
    private File directory() {
        File directory = new File(getContext().getFilesDir(), "log-saf"); directory.mkdirs(); return directory;
    }
    private File document(String id) throws FileNotFoundException {
        if ("root".equals(id)) return directory();
        if (!id.matches("[a-zA-Z0-9_.-]{1,100}") || id.equals(".") || id.equals("..")) throw new FileNotFoundException("Invalid fixture document");
        return new File(directory(), id);
    }
    @Override public boolean onCreate() {
        try {
            File desktop = new File(directory(), "desktop.adi");
            if (!desktop.exists()) try (InputStream in = getContext().getAssets().open("desktop.adi"); FileOutputStream out = new FileOutputStream(desktop)) {
                byte[] buffer = new byte[4096]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
        } catch (IOException error) { throw new RuntimeException(error); }
        return true;
    }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? ROOTS : projection);
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, "r26").add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "root")
            .add(DocumentsContract.Root.COLUMN_TITLE, "R26 test logs").add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_CREATE)
            .add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*").add(DocumentsContract.Root.COLUMN_ICON, android.R.drawable.ic_menu_save);
        return cursor;
    }
    private void add(MatrixCursor cursor, String id) throws FileNotFoundException {
        File file = document(id); int flags = file.isDirectory() ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : DocumentsContract.Document.FLAG_SUPPORTS_WRITE | DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
        cursor.newRow().add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id).add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, id.equals("root") ? "R26 test logs" : id)
            .add(DocumentsContract.Document.COLUMN_MIME_TYPE, file.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : id.endsWith(".adi") ? "application/x-adif" : "application/octet-stream")
            .add(DocumentsContract.Document.COLUMN_FLAGS, flags).add(DocumentsContract.Document.COLUMN_SIZE, file.length()).add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCS : projection); add(cursor, id); return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String order) throws FileNotFoundException {
        if (!parent.equals("root")) throw new FileNotFoundException("Not a fixture directory");
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCS : projection);
        for (File file : directory().listFiles()) add(cursor, file.getName()); return cursor;
    }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (!parent.equals("root")) throw new FileNotFoundException("Not a fixture directory");
        File file = document(name);
        try { if (!file.createNewFile()) throw new IOException("Fixture already exists"); }
        catch (IOException e) { throw new FileNotFoundException(e.toString()); }
        return name;
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException { if (id.equals("root") || !document(id).delete()) throw new FileNotFoundException("Cannot delete fixture"); }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        return ParcelFileDescriptor.open(document(id), ParcelFileDescriptor.parseMode(mode));
    }
}
