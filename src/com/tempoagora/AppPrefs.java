package com.tempoagora;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Preferências pequenas (lugares, unidades, flags). Previsões ficam em WeatherCache. */
public final class AppPrefs {
    private static final String NAME = "tempo_agora";
    private static final String KEY_FAVORITES = "favorites";
    private static final String KEY_DEVICE = "last_current";
    private static final String KEY_SELECTED = "selected_place";
    private static final String KEY_WIDGET = "widget_place";
    private static final String KEY_TEMP = "unit_temp";
    private static final String KEY_WIND = "unit_wind";
    private static final String KEY_PRECIP = "unit_precip";
    private static final String KEY_LOCATION_ASKED = "location_asked";
    private static final String KEY_LAST_FIX = "last_fix_at";
    private static final String LEGACY_CACHE_PREFIX = "weather_";

    private AppPrefs() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static List<Place> favorites(Context context) {
        List<Place> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(KEY_FAVORITES, "[]"));
            for (int i = 0; i < array.length(); i++) {
                Place place = Place.fromJson(array.optJSONObject(i));
                if (place != null && !containsKey(out, place)) out.add(place);
            }
        } catch (JSONException corrupted) {
            return new ArrayList<>();
        }
        return out;
    }

    public static void saveFavorites(Context context, List<Place> favorites) {
        JSONArray array = new JSONArray();
        for (Place place : favorites) array.put(place.toJson());
        prefs(context).edit().putString(KEY_FAVORITES, array.toString()).apply();
    }

    public static Place devicePlace(Context context) {
        return readPlace(context, KEY_DEVICE);
    }

    public static void setDevicePlace(Context context, Place place) {
        writePlace(context, KEY_DEVICE, place);
    }

    /** Último lugar escolhido pelo usuário (pode ser "segue a localização"). */
    public static Place selectedPlace(Context context) {
        return readPlace(context, KEY_SELECTED);
    }

    public static void setSelectedPlace(Context context, Place place) {
        writePlace(context, KEY_SELECTED, place);
    }

    public static Place widgetPlace(Context context) {
        return readPlace(context, KEY_WIDGET);
    }

    public static void setWidgetPlace(Context context, Place place) {
        writePlace(context, KEY_WIDGET, place);
    }

    /** Lugar que o widget deve mostrar agora. */
    public static Place widgetPlaceResolved(Context context) {
        Place device = devicePlace(context);
        Place widget = widgetPlace(context);
        if (widget != null) return widget.currentLocation && device != null ? device : widget;
        Place selected = selectedPlace(context);
        if (selected != null) {
            Place resolved = selected.currentLocation ? device : selected;
            if (resolved != null) return resolved;
        }
        if (device != null) return device;
        List<Place> favorites = favorites(context);
        return favorites.isEmpty() ? null : favorites.get(0);
    }

    public static Units units(Context context) {
        SharedPreferences prefs = prefs(context);
        return new Units(prefs.getString(KEY_TEMP, Units.TEMP_C),
                prefs.getString(KEY_WIND, Units.WIND_KMH),
                prefs.getString(KEY_PRECIP, Units.PRECIP_MM));
    }

    public static void setUnits(Context context, Units units) {
        prefs(context).edit()
                .putString(KEY_TEMP, units.temperature)
                .putString(KEY_WIND, units.wind)
                .putString(KEY_PRECIP, units.precipitation)
                .apply();
    }

    public static boolean locationAsked(Context context) {
        return prefs(context).getBoolean(KEY_LOCATION_ASKED, false);
    }

    public static void setLocationAsked(Context context) {
        prefs(context).edit().putBoolean(KEY_LOCATION_ASKED, true).apply();
    }

    public static long lastFixAt(Context context) {
        return prefs(context).getLong(KEY_LAST_FIX, 0);
    }

    public static void setLastFixAt(Context context, long millis) {
        prefs(context).edit().putLong(KEY_LAST_FIX, millis).apply();
    }

    /** Remove o cache antigo que ficava em SharedPreferences (uma entrada por coordenada). */
    public static void removeLegacyCache(Context context) {
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(LEGACY_CACHE_PREFIX)) {
                editor.remove(key);
                changed = true;
            }
        }
        if (changed) editor.apply();
    }

    /** Favoritos e ajustes em JSON, para o usuário guardar onde quiser (SAF). */
    public static JSONObject exportBackup(Context context) throws JSONException {
        JSONObject out = new JSONObject();
        out.put("app", "atemporal");
        out.put("format", 1);
        out.put("exportedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date()));
        JSONArray favorites = new JSONArray();
        for (Place place : favorites(context)) favorites.put(place.toJson());
        out.put("favorites", favorites);
        Place widget = widgetPlace(context);
        if (widget != null) out.put("widgetPlace", widget.toJson());
        Place selected = selectedPlace(context);
        if (selected != null) out.put("selectedPlace", selected.toJson());
        Units units = units(context);
        JSONObject unitsJson = new JSONObject();
        unitsJson.put("temperature", units.temperature);
        unitsJson.put("wind", units.wind);
        unitsJson.put("precipitation", units.precipitation);
        out.put("units", unitsJson);
        return out;
    }

    /**
     * Mescla um backup: favoritos novos são acrescentados, os existentes são mantidos.
     * Devolve quantos favoritos novos entraram. Lança IllegalArgumentException se não for um backup.
     */
    public static int importBackup(Context context, JSONObject backup) {
        JSONArray incoming = backup == null ? null : backup.optJSONArray("favorites");
        if (incoming == null || !"atemporal".equals(backup.optString("app"))) {
            throw new IllegalArgumentException("Este arquivo não é um backup do Atemporal.");
        }
        List<Place> favorites = favorites(context);
        int added = 0;
        for (int i = 0; i < incoming.length(); i++) {
            Place place = Place.fromJson(incoming.optJSONObject(i));
            if (place == null || containsKey(favorites, place)) continue;
            favorites.add(place);
            added++;
        }
        saveFavorites(context, favorites);
        JSONObject units = backup.optJSONObject("units");
        if (units != null) {
            setUnits(context, new Units(units.optString("temperature", Units.TEMP_C),
                    units.optString("wind", Units.WIND_KMH),
                    units.optString("precipitation", Units.PRECIP_MM)));
        }
        Place widget = Place.fromJson(backup.optJSONObject("widgetPlace"));
        if (widget != null) setWidgetPlace(context, widget);
        Place selected = Place.fromJson(backup.optJSONObject("selectedPlace"));
        if (selected != null) setSelectedPlace(context, selected);
        return added;
    }

    private static Place readPlace(Context context, String key) {
        String json = prefs(context).getString(key, "");
        if (json.isEmpty()) return null;
        try {
            return Place.fromJson(new JSONObject(json));
        } catch (JSONException corrupted) {
            return null;
        }
    }

    private static void writePlace(Context context, String key, Place place) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (place == null) editor.remove(key);
        else editor.putString(key, place.toJson().toString());
        editor.apply();
    }

    private static boolean containsKey(List<Place> places, Place candidate) {
        for (Place place : places) if (place.sameKey(candidate)) return true;
        return false;
    }
}
