package com.tempoagora;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Home-screen weather card. It renders cached data immediately and refreshes in the background. */
public final class WeatherWidgetProvider extends AppWidgetProvider {
    private static final String PREFERENCES = "tempo_agora";
    private static final ExecutorService REFRESHER = Executors.newSingleThreadExecutor();

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        render(context, manager, ids);
        if (ids == null || ids.length == 0) return;
        PendingResult pending = goAsync();
        REFRESHER.execute(() -> {
            try {
                Place place = selectedPlace(context);
                if (place == null) return;
                List<Place> places = new ArrayList<>();
                places.add(place);
                List<WeatherSnapshot> snapshots = new WeatherApiClient().fetch(places);
                if (snapshots.isEmpty()) return;
                WeatherSnapshot snapshot = snapshots.get(0);
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                        .putString(cacheKey(snapshot.place), snapshot.toJson().toString()).apply();
                renderAll(context);
            } catch (Exception ignored) {
                // Keep the last cached forecast visible when the network is unavailable.
            } finally {
                pending.finish();
            }
        });
    }

    /** Refreshes visible instances from the app's already updated local cache. */
    public static void refreshViews(Context context) {
        renderAll(context.getApplicationContext());
    }

    private static void renderAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, WeatherWidgetProvider.class));
        render(context, manager, ids);
    }

    private static void render(Context context, AppWidgetManager manager, int[] ids) {
        if (ids == null) return;
        for (int id : ids) manager.updateAppWidget(id, buildViews(context, id));
    }

    private static RemoteViews buildViews(Context context, int widgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_weather);
        Place place = selectedPlace(context);
        WeatherSnapshot snapshot = place == null ? null : readSnapshot(context, place);
        updateDailyForecast(views, snapshot);

        views.setTextViewText(R.id.widget_place, place == null ? "Atemporal" : place.name);
        views.setTextViewText(R.id.widget_subtitle, place == null ? "Escolha uma cidade no app" : place.subtitle());
        if (snapshot == null) {
            views.setTextViewText(R.id.widget_temperature, "—°");
            views.setTextViewText(R.id.widget_condition, place == null ? "Toque para escolher uma cidade" : "Toque para carregar a previsão");
            views.setTextViewText(R.id.widget_icon, "☁");
            views.setTextViewText(R.id.widget_feels, "Sensação —");
            views.setTextViewText(R.id.widget_rain, "Chuva —");
            views.setTextViewText(R.id.widget_humidity, "Umidade —");
            views.setTextViewText(R.id.widget_updated, "Abrir Atemporal");
        } else {
            WeatherSnapshot.Hourly next = nextHour(snapshot);
            views.setTextViewText(R.id.widget_temperature, number(snapshot.currentTemperature, 0, "°"));
            views.setTextViewText(R.id.widget_condition, condition(snapshot.currentWeatherCode));
            views.setTextViewText(R.id.widget_icon, icon(snapshot.currentWeatherCode));
            views.setTextViewText(R.id.widget_feels, "Sensação " + number(snapshot.currentFeelsLike, 0, "°"));
            String rain = next == null
                    ? number(snapshot.currentPrecipitation, 1, " mm") + " agora"
                    : number(next.precipitation, 1, " mm") + " · " + number(next.precipitationProbability, 0, "%");
            views.setTextViewText(R.id.widget_rain, "Chuva " + rain);
            views.setTextViewText(R.id.widget_humidity, "Umidade " + number(snapshot.currentHumidity, 0, "%"));
            String updated = snapshot.currentTime != null && snapshot.currentTime.length() >= 16
                    ? snapshot.currentTime.substring(11, 16) : "agora";
            views.setTextViewText(R.id.widget_updated, "Atualizado " + updated + " · ↻");
        }

        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(context, 71, openIntent, pendingFlags);
        views.setOnClickPendingIntent(R.id.widget_root, open);

        Intent refreshIntent = new Intent(context, WeatherWidgetProvider.class);
        refreshIntent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        refreshIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[]{widgetId});
        PendingIntent refresh = PendingIntent.getBroadcast(context, 7000 + widgetId, refreshIntent, pendingFlags);
        views.setOnClickPendingIntent(R.id.widget_updated, refresh);
        return views;
    }

    private static void updateDailyForecast(RemoteViews views, WeatherSnapshot snapshot) {
        int[] labels = {
                R.id.widget_day_1_label, R.id.widget_day_2_label, R.id.widget_day_3_label, R.id.widget_day_4_label, R.id.widget_day_5_label,
                R.id.widget_day_6_label, R.id.widget_day_7_label, R.id.widget_day_8_label, R.id.widget_day_9_label, R.id.widget_day_10_label,
                R.id.widget_day_11_label, R.id.widget_day_12_label, R.id.widget_day_13_label, R.id.widget_day_14_label, R.id.widget_day_15_label
        };
        int[] temperatures = {
                R.id.widget_day_1_temp, R.id.widget_day_2_temp, R.id.widget_day_3_temp, R.id.widget_day_4_temp, R.id.widget_day_5_temp,
                R.id.widget_day_6_temp, R.id.widget_day_7_temp, R.id.widget_day_8_temp, R.id.widget_day_9_temp, R.id.widget_day_10_temp,
                R.id.widget_day_11_temp, R.id.widget_day_12_temp, R.id.widget_day_13_temp, R.id.widget_day_14_temp, R.id.widget_day_15_temp
        };
        int[] rain = {
                R.id.widget_day_1_rain, R.id.widget_day_2_rain, R.id.widget_day_3_rain, R.id.widget_day_4_rain, R.id.widget_day_5_rain,
                R.id.widget_day_6_rain, R.id.widget_day_7_rain, R.id.widget_day_8_rain, R.id.widget_day_9_rain, R.id.widget_day_10_rain,
                R.id.widget_day_11_rain, R.id.widget_day_12_rain, R.id.widget_day_13_rain, R.id.widget_day_14_rain, R.id.widget_day_15_rain
        };
        int[] probabilities = {
                R.id.widget_day_1_probability, R.id.widget_day_2_probability, R.id.widget_day_3_probability, R.id.widget_day_4_probability, R.id.widget_day_5_probability,
                R.id.widget_day_6_probability, R.id.widget_day_7_probability, R.id.widget_day_8_probability, R.id.widget_day_9_probability, R.id.widget_day_10_probability,
                R.id.widget_day_11_probability, R.id.widget_day_12_probability, R.id.widget_day_13_probability, R.id.widget_day_14_probability, R.id.widget_day_15_probability
        };
        for (int i = 0; i < labels.length; i++) {
            WeatherSnapshot.Daily day = snapshot == null || snapshot.daily.size() <= i
                    ? null : snapshot.daily.get(i);
            String dayName = "—";
            if (day != null) {
                if (i == 0) dayName = "Hoje";
                else if (i == 1) dayName = "Amanhã";
                else dayName = shortWeekday(day.date) + " " + day.date.substring(8, 10);
            }
            views.setTextViewText(labels[i], dayName);
            views.setTextViewText(temperatures[i], day == null ? "—" : number(day.maxTemperature, 0, "°")
                    + " / " + number(day.minTemperature, 0, "°"));
            views.setTextViewText(rain[i], day == null ? "—" : number(day.precipitation, 1, " mm"));
            views.setTextViewText(probabilities[i], day == null ? "—" : number(day.precipitationProbability, 0, "%"));
        }
    }

    private static String shortWeekday(String isoDate) {
        try {
            SimpleDateFormat input = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            input.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = input.parse(isoDate);
            SimpleDateFormat output = new SimpleDateFormat("EEE", new Locale("pt", "BR"));
            output.setTimeZone(TimeZone.getTimeZone("UTC"));
            String day = output.format(date);
            return day.substring(0, 1).toUpperCase(new Locale("pt", "BR")) + day.substring(1, 3);
        } catch (Exception ignored) { return isoDate; }
    }

    private static Place selectedPlace(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        Place place = readPlace(prefs.getString("widget_place", ""));
        if (place != null) return place;
        place = readPlace(prefs.getString("last_current", ""));
        if (place != null) return place;
        place = readPlace(prefs.getString("last_manual", ""));
        if (place != null) return place;
        try {
            JSONArray favorites = new JSONArray(prefs.getString("favorites", "[]"));
            if (favorites.length() > 0) return Place.fromJson(favorites.optJSONObject(0));
        } catch (Exception ignored) { }
        return null;
    }

    private static Place readPlace(String json) {
        if (json == null || json.isEmpty()) return null;
        try { return Place.fromJson(new JSONObject(json)); }
        catch (Exception ignored) { return null; }
    }

    private static WeatherSnapshot readSnapshot(Context context, Place place) {
        try {
            String json = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .getString(cacheKey(place), "");
            return json.isEmpty() ? null : WeatherSnapshot.fromJson(new JSONObject(json), place);
        } catch (Exception ignored) { return null; }
    }

    private static String cacheKey(Place place) { return "weather_" + place.key().replace(',', '_'); }

    private static WeatherSnapshot.Hourly nextHour(WeatherSnapshot snapshot) {
        String now = snapshot.currentTime;
        if (now == null || now.length() < 13) return snapshot.hourly.isEmpty() ? null : snapshot.hourly.get(0);
        String currentHour = now.substring(0, 13);
        for (WeatherSnapshot.Hourly hour : snapshot.hourly) {
            if (hour.time.length() >= 13 && hour.time.substring(0, 13).compareTo(currentHour) > 0) return hour;
        }
        return null;
    }

    private static String number(double value, int decimals, String suffix) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "—";
        NumberFormat format = NumberFormat.getNumberInstance(new Locale("pt", "BR"));
        format.setMinimumFractionDigits(decimals);
        format.setMaximumFractionDigits(decimals);
        return format.format(value) + suffix;
    }

    private static String condition(int code) {
        if (code == 0) return "Céu limpo";
        if (code == 1) return "Predominantemente limpo";
        if (code == 2) return "Parcialmente nublado";
        if (code == 3) return "Nublado";
        if (code == 45 || code == 48) return "Neblina";
        if (code >= 51 && code <= 57) return "Garoa";
        if (code >= 61 && code <= 67) return "Chuva";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Neve";
        if (code >= 80 && code <= 82) return "Pancadas de chuva";
        if (code >= 95) return "Trovoadas";
        return "Condição variável";
    }

    private static String icon(int code) {
        if (code == 0) return "☀";
        if (code == 1 || code == 2) return "🌤";
        if (code == 3) return "☁";
        if (code == 45 || code == 48) return "🌫";
        if (code >= 51 && code <= 67) return "🌧";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "❄";
        if (code >= 80 && code <= 82) return "🌦";
        if (code >= 95) return "⛈";
        return "☁";
    }
}
