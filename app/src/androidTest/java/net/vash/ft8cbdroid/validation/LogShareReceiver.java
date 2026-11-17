package net.vash.ft8cbdroid.validation;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** External receiver proves that only an explicitly granted ADIF URI can be read. */
public final class LogShareReceiver extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String result;
        try {
            Uri uri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            byte[] bytes;
            try (InputStream stream = getContentResolver().openInputStream(uri)) { ByteArrayOutputStream buffer = new ByteArrayOutputStream(); byte[] chunk = new byte[8192]; int count; while ((count = stream.read(chunk)) != -1) buffer.write(chunk, 0, count); bytes = buffer.toByteArray(); }
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format("%02x", b & 255));
            result = "read:" + hex;
        } catch (SecurityException expected) { result = "denied"; }
        catch (Exception error) { result = "error:" + error; }
        try (FileOutputStream out = openFileOutput("log-share-result", MODE_PRIVATE)) { out.write(result.getBytes(StandardCharsets.UTF_8)); }
        catch (IOException error) { throw new RuntimeException(error); }
        finish();
    }
}
