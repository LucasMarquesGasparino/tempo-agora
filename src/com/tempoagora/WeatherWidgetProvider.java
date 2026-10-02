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
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Home-screen weather card. It renders cached data immediately and refreshes in the background. */
public final class WeatherWidgetProvider extends AppWidgetProvider {
    private static final String PREFERENCES = "tempo_agora";
    private static final String ACTION_REFRESH = "com.tempoagora.action.REFRESH_WIDGET";
    private static final ExecutorService REFRESHER = Executors.newSingleThreadExecutor();

    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
            if (ids == null || ids.length == 0) {
                ids = manager.getAppWidgetIds(new ComponentName(context, WeatherWidgetProvider.class));
            }
            showRefreshing(context, manager, ids);
            requestRefresh(context, ids);
            return;
        }
        super.onReceive(context, intent);
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        render(context, manager, ids);
        requestRefresh(context, ids);
    }

    private void requestRefresh(Context context, int[] ids) {
        if (ids == null || ids.length == 0) return;
        PendingResult pending = goAsync();
        REFRESHER.execute(() -> {
            try {
                Place place = selectedPlace(context);
                if (place != null) {
                    List<Place> places = new ArrayList<>();
                    places.add(place);
                    List<WeatherSnapshot> snapshots = new WeatherApiClient().fetch(places);
                    if (!snapshots.isEmpty()) {
                        WeatherSnapshot snapshot = snapshots.get(0);
                        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                                .putString(cacheKey(snapshot.place), snapshot.toJson().toString()).apply();
                    }
                }
            } catch (Exception ignored) {
                // Keep the last cached forecast visible when the network is unavailable.
            } finally {
                renderAll(context);
                pending.finish();
            }
        });
    }

    private static void showRefreshing(Context context, AppWidgetManager manager, int[] ids) {
        if (ids == null) return;
        for (int id : ids) {
            RemoteViews views = buildViews(context, id);
            views.setTextViewText(R.id.widget_updated, "Buscando previsão atualizada…");
            manager.updateAppWidget(id, views);
        }
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
        updateForecast(context, views, snapshot);

        views.setTextViewText(R.id.widget_place, place == null ? "Atemporal" : place.name);
        views.setTextViewText(R.id.widget_subtitle, place == null ? "Escolha uma cidade no app" : place.subtitle());
        views.setTextViewText(R.id.widget_date, widgetDate(snapshot));
        if (snapshot == null) {
            views.setTextViewText(R.id.widget_temperature, "—°");
            views.setTextViewText(R.id.widget_condition, place == null ? "Toque para escolher uma cidade" : "Toque para carregar a previsão");
            views.setTextViewText(R.id.widget_icon, "☁");
            views.setTextViewText(R.id.widget_feels, "Sensação —");
            views.setTextViewText(R.id.widget_rain, "Chuva —");
            views.setTextViewText(R.id.widget_humidity, "Umidade —");
            views.setTextViewText(R.id.widget_updated, "Sem dados recentes");
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
            views.setTextViewText(R.id.widget_updated, "Atualizado " + updated);
        }

        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(context, 71, openIntent, pendingFlags);
        views.setOnClickPendingIntent(R.id.widget_root, open);

        Intent refreshIntent = new Intent(context, WeatherWidgetProvider.class);
        refreshIntent.setAction(ACTION_REFRESH);
        refreshIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[]{widgetId});
        PendingIntent refresh = PendingIntent.getBroadcast(context, 7000 + widgetId, refreshIntent, pendingFlags);
        views.setOnClickPendingIntent(R.id.widget_refresh, refresh);
        return views;
    }

    private static void updateForecast(Context context, RemoteViews views, WeatherSnapshot snapshot) {
        List<WeatherSnapshot.Hourly> hours = snapshot == null
                ? new ArrayList<WeatherSnapshot.Hourly>() : todayHours(snapshot);
        List<WeatherSnapshot.Daily> days = upcomingDays(snapshot);
        boolean hasHours = !hours.isEmpty();
        boolean hasDays = !days.isEmpty();
        int todayVisibility = hasHours ? android.view.View.VISIBLE : android.view.View.GONE;
        int daysVisibility = hasDays ? android.view.View.VISIBLE : android.view.View.GONE;

        views.setViewVisibility(R.id.widget_today_title, todayVisibility);
        views.setViewVisibility(R.id.widget_today_table, todayVisibility);
        views.setViewVisibility(R.id.widget_days_title, daysVisibility);
        views.setViewVisibility(R.id.widget_days_table, daysVisibility);
        views.setViewVisibility(R.id.widget_forecast_section_divider,
                hasHours && hasDays ? android.view.View.VISIBLE : android.view.View.GONE);
        views.removeAllViews(R.id.widget_today_rows);
        views.removeAllViews(R.id.widget_days_rows);

        for (int i = 0; i < hours.size(); i++) {
            WeatherSnapshot.Hourly hour = hours.get(i);
            String time = hour.time.substring(11, 13) + "h";
            RemoteViews row = forecastRow(context, time,
                    number(hour.temperature, 0, "°"),
                    number(hour.precipitation, 1, " mm"),
                    number(hour.precipitationProbability, 0, "%"),
                    i < hours.size() - 1);
            views.addView(R.id.widget_today_rows, row);
        }

        for (int i = 0; i < days.size(); i++) {
            WeatherSnapshot.Daily day = days.get(i);
            RemoteViews row = forecastRow(context, dailyLabel(snapshot, day),
                    number(day.maxTemperature, 0, "°") + " / " + number(day.minTemperature, 0, "°"),
                    number(day.precipitation, 1, " mm"),
                    number(day.precipitationProbability, 0, "%"),
                    i < days.size() - 1);
            views.addView(R.id.widget_days_rows, row);
        }
    }

    private static RemoteViews forecastRow(Context context, String label, String temperature,
                                           String precipitation, String probability,
                                           boolean showDivider) {
        RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_forecast_row);
        row.setTextViewText(R.id.widget_forecast_cell_label, label);
        row.setTextViewText(R.id.widget_forecast_cell_temp, temperature);
        row.setTextViewText(R.id.widget_forecast_cell_rain, precipitation);
        row.setTextViewText(R.id.widget_forecast_cell_probability, probability);
        row.setViewVisibility(R.id.widget_forecast_row_divider,
                showDivider ? android.view.View.VISIBLE : android.view.View.GONE);
        return row;
    }

    private static List<WeatherSnapshot.Daily> upcomingDays(WeatherSnapshot snapshot) {
        List<WeatherSnapshot.Daily> result = new ArrayList<>();
        if (snapshot == null) return result;
        for (int i = 1; i < snapshot.daily.size() && result.size() < 7; i++) {
            WeatherSnapshot.Daily day = snapshot.daily.get(i);
            if (day == null || day.date == null || day.date.length() < 10) continue;
            if (!hasValue(day.maxTemperature) && !hasValue(day.minTemperature)
                    && !hasValue(day.precipitation) && !hasValue(day.precipitationProbability)) continue;
            result.add(day);
        }
        return result;
    }

    private static List<WeatherSnapshot.Hourly> todayHours(WeatherSnapshot snapshot) {
        List<WeatherSnapshot.Hourly> result = new ArrayList<>();
        String now = snapshot.currentTime;
        if (now == null || now.length() < 13) return result;
        String today = now.substring(0, 10);
        String currentHour = now.substring(0, 13);
        for (WeatherSnapshot.Hourly hour : snapshot.hourly) {
            if (hour.time.length() < 13 || !hour.time.startsWith(today)
                    || hour.time.substring(0, 13).compareTo(currentHour) <= 0) continue;
            int hourOfDay;
            try { hourOfDay = Integer.parseInt(hour.time.substring(11, 13)); }
            catch (NumberFormatException ignored) { continue; }
            if (hourOfDay % 3 != 0) continue;
            if (!hasValue(hour.temperature) && !hasValue(hour.precipitation)
                    && !hasValue(hour.precipitationProbability)) continue;
            result.add(hour);
            if (result.size() == 8) break;
        }
        return result;
    }

    private static boolean hasValue(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static String widgetDate(WeatherSnapshot snapshot) {
        if (snapshot != null && snapshot.currentTime != null && snapshot.currentTime.length() >= 10) {
            String date = snapshot.currentTime.substring(0, 10);
            return date.substring(8, 10) + "/" + date.substring(5, 7);
        }
        return new SimpleDateFormat("dd/MM", new Locale("pt", "BR")).format(new Date());
    }

    private static String dailyLabel(WeatherSnapshot snapshot, WeatherSnapshot.Daily day) {
        if (snapshot.currentTime != null && snapshot.currentTime.length() >= 10) {
            try {
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                TimeZone utc = TimeZone.getTimeZone("UTC");
                dateFormat.setTimeZone(utc);
                Date today = dateFormat.parse(snapshot.currentTime.substring(0, 10));
                Calendar calendar = Calendar.getInstance(utc, Locale.US);
                calendar.setTime(today);
                calendar.add(Calendar.DAY_OF_MONTH, 1);
                if (day.date.equals(dateFormat.format(calendar.getTime()))) return "Amanhã";
            } catch (Exception ignored) { }
        }
        return shortWeekday(day.date) + " " + day.date.substring(8, 10);
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
