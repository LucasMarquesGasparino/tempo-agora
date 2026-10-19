package com.tempoagora;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Cache de previsões em arquivos atômicos, um por lugar, em noBackupFilesDir.
 * Todo acesso é síncrono: nunca chame da UI thread.
 */
public final class WeatherCache {
    private static final Object LOCK = new Object();
    private static final String PREFIX = "w_";
    private static final String SUFFIX = ".json";

    private WeatherCache() { }

    /** Devolve null se não há cache para o lugar ou se o arquivo estava corrompido (que é apagado). */
    public static WeatherSnapshot read(Context context, Place place) {
        synchronized (LOCK) {
            AtomicFile file = new AtomicFile(fileFor(context, place));
            byte[] bytes;
            try {
                bytes = file.readFully();
            } catch (IOException missing) {
                return null;
            }
            try {
                return WeatherSnapshot.fromJson(new JSONObject(new String(bytes, StandardCharsets.UTF_8)), place);
            } catch (JSONException corrupted) {
                file.delete();
                return null;
            }
        }
    }

    /** Grava de forma atômica. Devolve false se a escrita falhou (o cache antigo continua valendo). */
    public static boolean write(Context context, WeatherSnapshot snapshot) {
        synchronized (LOCK) {
            AtomicFile file = new AtomicFile(fileFor(context, snapshot.place));
            FileOutputStream out = null;
            try {
                out = file.startWrite();
                out.write(snapshot.toJson().toString().getBytes(StandardCharsets.UTF_8));
                file.finishWrite(out);
                return true;
            } catch (IOException failed) {
                if (out != null) file.failWrite(out);
                return false;
            }
        }
    }

    /** Apaga os arquivos de lugares que não estão em {@code keep}. */
    public static void prune(Context context, Collection<Place> keep) {
        Set<String> names = new HashSet<>();
        for (Place place : keep) names.add(fileName(place));
        synchronized (LOCK) {
            File[] files = directory(context).listFiles();
            if (files == null) return;
            for (File candidate : files) {
                String name = candidate.getName();
                if (!name.startsWith(PREFIX)) continue;
                String base = name.endsWith(".bak") ? name.substring(0, name.length() - 4) : name;
                if (!names.contains(base)) candidate.delete();
            }
        }
    }

    private static File directory(Context context) {
        File dir = new File(context.getNoBackupFilesDir(), "weather");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    private static File fileFor(Context context, Place place) {
        return new File(directory(context), fileName(place));
    }

    static String fileName(Place place) {
        return PREFIX + place.key().replaceAll("[^0-9A-Za-z.-]", "_") + SUFFIX;
    }
}
