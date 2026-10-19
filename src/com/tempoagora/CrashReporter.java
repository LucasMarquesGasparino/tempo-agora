package com.tempoagora;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Grava o erro de qualquer travamento em files/last_crash.txt e, no Android 10+,
 * em Download/atemporal-crash.txt, para dar para diagnosticar sem adb.
 */
final class CrashReporter {
    static final String FILE_NAME = "last_crash.txt";

    private CrashReporter() {
    }

    static void install(Context context) {
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                String report = report(app, thread, error);
                writePrivate(app, report);
                writeDownloads(app, report);
            } catch (Throwable ignored) {
                // Nunca deixar o relatório esconder o erro original.
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private static String report(Context context, Thread thread, Throwable error) {
        StringWriter text = new StringWriter();
        PrintWriter out = new PrintWriter(text);
        out.println("Atemporal: travamento");
        out.println("Quando: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()));
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            out.println("App: " + info.versionName + " (" + info.versionCode + ")");
        } catch (Exception ignored) {
            out.println("App: versão desconhecida");
        }
        out.println("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        out.println("Aparelho: " + Build.MANUFACTURER + " " + Build.MODEL);
        out.println("Thread: " + thread.getName());
        out.println();
        error.printStackTrace(out);
        out.flush();
        return text.toString();
    }

    private static void writePrivate(Context context, String report) throws Exception {
        try (OutputStream out = new FileOutputStream(new File(context.getFilesDir(), FILE_NAME))) {
            out.write(report.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeDownloads(Context context, String report) throws Exception {
        if (Build.VERSION.SDK_INT < 29) return;
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "atemporal-crash.txt");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/");
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return;
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out != null) out.write(report.getBytes(StandardCharsets.UTF_8));
        }
    }
}
