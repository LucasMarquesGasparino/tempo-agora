package com.tempoagora;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Renderiza o widget (tamanho compacto ou completo) e agenda as atualizações. Nunca na UI thread. */
final class WidgetUpdater {
    static final int JOB_PERIODIC = 4101;
    static final int JOB_CATCH_UP = 4102;
    private static final long STALE_MILLIS = 30 * 60_000L;
    private static final long OLD_DATA_MILLIS = 3 * 3_600_000L;
    private static final int TICK_REQUEST = 9100;
    private static final int OPEN_REQUEST = 71;
    private static final int REFRESH_REQUEST_BASE = 7000;
    private static final int MAX_HOURS = 8;
    private static final int MAX_DAYS = 7;
    private static final int MAX_SIZES = 4;
    private static final int DEFAULT_WIDTH_DP = 250;
    private static final int DEFAULT_HEIGHT_DP = 430;
    private static final ExecutorService BACKGROUND = Executors.newSingleThreadExecutor();

    private WidgetUpdater() { }

    static void renderAll(Context context) {
        renderAll(context, null);
    }

    /** Redesenha todas as instâncias. statusOverride, se não nulo, substitui a linha de rodapé. */
    static void renderAll(Context context, String statusOverride) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, WeatherWidgetProvider.class));
        for (int id : ids) manager.updateAppWidget(id, build(context, id, statusOverride));
        if (ids.length > 0) scheduleTick(context);
    }

    /** Chamado pelo app quando lugar ou dados mudam: redesenha em background e pede atualização se estiver velho. */
    static void onPlaceOrDataChanged(Context context) {
        Context app = context.getApplicationContext();
        BACKGROUND.execute(() -> {
            renderAll(app);
            requestIfStale(app);
        });
    }

    static RemoteViews build(Context context, int id, String statusOverride) {
        Place place = AppPrefs.widgetPlaceResolved(context);
        WeatherSnapshot snapshot = place == null ? null : WeatherCache.read(context, place);
        Units units = AppPrefs.units(context);
        Bundle options = AppWidgetManager.getInstance(context).getAppWidgetOptions(id);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ArrayList<SizeF> sizes = options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES);
            if (sizes != null && !sizes.isEmpty()) {
                Map<SizeF, RemoteViews> bySize = new HashMap<>();
                for (SizeF size : sizes) {
                    if (bySize.size() >= MAX_SIZES) break;
                    bySize.put(size, buildForSize(context, id, place, snapshot, units, statusOverride,
                            (int) size.getWidth(), (int) size.getHeight()));
                }
                return new RemoteViews(bySize);
            }
        }
        int width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        return buildForSize(context, id, place, snapshot, units, statusOverride,
                width > 0 ? width : DEFAULT_WIDTH_DP, height > 0 ? height : DEFAULT_HEIGHT_DP);
    }

    private static RemoteViews buildForSize(Context context, int id, Place place, WeatherSnapshot snapshot,
                                            Units units, String statusOverride, int widthDp, int heightDp) {
        String packageName = context.getPackageName();
        WeatherSnapshot.Now now = snapshot == null ? null : snapshot.now(System.currentTimeMillis());
        String today = snapshot == null ? deviceToday() : snapshot.localToday();
        String placeName = place == null ? "Atemporal" : place.name;
        String condition = now != null ? WeatherCodes.condition(now.weatherCode)
                : place == null ? "Escolha uma cidade no app" : "Toque para carregar a previsão";
        String footer = footer(snapshot, today, statusOverride);

        if (WidgetSizing.compact(widthDp, heightDp)) {
            RemoteViews views = new RemoteViews(packageName, R.layout.widget_weather_small);
            views.setTextViewText(R.id.widget_place, placeName);
            views.setTextViewText(R.id.widget_temperature, now == null ? "—°" : Format.temperature(now.temperature, units));
            views.setTextViewText(R.id.widget_condition, condition);
            setIcon(views, R.id.widget_icon, now, condition);
            views.setTextViewText(R.id.widget_updated, footer);
            views.setOnClickPendingIntent(R.id.widget_root, openIntent(context));
            return views;
        }

        RemoteViews views = new RemoteViews(packageName, R.layout.widget_weather);
        views.setTextViewText(R.id.widget_place, placeName);
        views.setTextViewText(R.id.widget_subtitle, place == null ? "Escolha uma cidade no app" : place.subtitle());
        views.setTextViewText(R.id.widget_date, Format.weekdayDayMonth(today));
        views.setTextViewText(R.id.widget_temperature, now == null ? "—°" : Format.temperature(now.temperature, units));
        views.setTextViewText(R.id.widget_condition, condition);
        setIcon(views, R.id.widget_icon, now, condition);
        views.setTextViewText(R.id.widget_feels, now == null ? "Sensação —"
                : "Sensação " + Format.temperature(now.feelsLike, units));
        views.setTextViewText(R.id.widget_minmax, minMax(snapshot, today, units));
        views.setTextViewText(R.id.widget_rain, rainText(snapshot, now, units));
        views.setTextViewText(R.id.widget_humidity, now == null ? "Umidade —" : "Umidade " + Format.percent(now.humidity));
        views.setTextViewText(R.id.widget_wind, windText(now, units));
        views.setTextViewText(R.id.widget_updated, footer);
        bindForecastTables(context, views, snapshot, today, heightDp, units);
        views.setOnClickPendingIntent(R.id.widget_root, openIntent(context));
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshIntent(context, id));
        return views;
    }

    private static void setIcon(RemoteViews views, int viewId, WeatherSnapshot.Now now, String condition) {
        int code = now == null ? -1 : now.weatherCode;
        boolean day = now == null || now.isDay;
        views.setImageViewResource(viewId, WeatherIcons.res(code, day));
        views.setContentDescription(viewId, condition);
    }

    private static String minMax(WeatherSnapshot snapshot, String today, Units units) {
        if (snapshot == null) return "";
        List<WeatherSnapshot.Daily> days = snapshot.upcomingDaily();
        if (days.isEmpty() || !today.equals(days.get(0).date)) return "";
        WeatherSnapshot.Daily day = days.get(0);
        return "Máx " + Format.temperature(day.maxTemperature, units)
                + " · Mín " + Format.temperature(day.minTemperature, units);
    }

    private static String rainText(WeatherSnapshot snapshot, WeatherSnapshot.Now now, Units units) {
        if (snapshot == null) return "Chuva —";
        List<Integer> upcoming = ForecastTime.remainingToday(snapshot.hourlyTimes(), snapshot.localNow(), false);
        if (!upcoming.isEmpty()) {
            WeatherSnapshot.Hourly next = snapshot.hourly.get(upcoming.get(0));
            return "Chuva " + Format.precipitation(next.precipitation, units)
                    + " · " + Format.percent(next.precipitationProbability);
        }
        return now == null ? "Chuva —" : "Chuva " + Format.precipitation(now.precipitation, units);
    }

    private static String windText(WeatherSnapshot.Now now, Units units) {
        if (now == null) return "Vento —";
        String compass = Format.compass(now.windDirection);
        return "Vento " + Format.wind(now.windSpeed, units) + (compass.isEmpty() ? "" : " " + compass);
    }

    /** Rodapé: "Atualizado às HH:MM", ou um aviso quando os dados estão velhos ou ausentes. */
    static String footer(WeatherSnapshot snapshot, String today, String statusOverride) {
        if (statusOverride != null) return statusOverride;
        if (snapshot == null) return "Sem dados recentes";
        if (snapshot.fetchedAt == 0) return "Atualizado às " + Format.clock(snapshot.currentTime);
        long fetched = snapshot.fetchedAt;
        if (System.currentTimeMillis() - fetched > OLD_DATA_MILLIS) {
            String fetchedDay = isoDay(fetched);
            if (fetchedDay.equals(today)) return "Dados de " + Format.clockOf(fetched) + " · sem atualizar";
            return "Dados de " + dayMonthClock(fetched);
        }
        return "Atualizado às " + Format.clockOf(fetched);
    }

    /** Texto para estados de erro: prefixo + "· dados de HH:MM" quando há cache. */
    static String staleNotice(Context context, String prefix) {
        Place place = AppPrefs.widgetPlaceResolved(context);
        WeatherSnapshot snapshot = place == null ? null : WeatherCache.read(context, place);
        if (snapshot == null) return prefix;
        String clock = snapshot.fetchedAt > 0 ? Format.clockOf(snapshot.fetchedAt) : Format.clock(snapshot.currentTime);
        return prefix + " · dados de " + clock;
    }

    private static void bindForecastTables(Context context, RemoteViews views, WeatherSnapshot snapshot,
                                           String today, int heightDp, Units units) {
        List<Integer> hourIndices = snapshot == null
                ? new ArrayList<Integer>() : availableHourIndices(snapshot);
        List<WeatherSnapshot.Daily> days = snapshot == null
                ? new ArrayList<WeatherSnapshot.Daily>() : availableDays(snapshot, today);
        int[] plan = WidgetSizing.plan(heightDp, Math.min(MAX_HOURS, hourIndices.size()),
                Math.min(MAX_DAYS, days.size()));
        List<Integer> shownHours = ForecastTime.sampleEvenly(hourIndices, plan[0]);
        List<WeatherSnapshot.Daily> shownDays = days.subList(0, plan[1]);

        boolean hasHours = plan[0] > 0;
        boolean hasDays = plan[1] > 0;
        views.setViewVisibility(R.id.widget_today_title, hasHours ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.widget_today_table, hasHours ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.widget_days_title, hasDays ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.widget_days_table, hasDays ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.widget_forecast_section_divider,
                hasHours && hasDays ? View.VISIBLE : View.GONE);
        views.removeAllViews(R.id.widget_today_rows);
        views.removeAllViews(R.id.widget_days_rows);

        for (int i = 0; i < shownHours.size(); i++) {
            WeatherSnapshot.Hourly hour = snapshot.hourly.get(shownHours.get(i));
            views.addView(R.id.widget_today_rows, forecastRow(context,
                    Format.hourLabel(hour.time), WeatherIcons.res(hour.weatherCode, hour.isDay),
                    Format.temperature(hour.temperature, units),
                    Format.precipitation(hour.precipitation, units),
                    Format.percent(hour.precipitationProbability),
                    i < shownHours.size() - 1));
        }
        for (int i = 0; i < shownDays.size(); i++) {
            WeatherSnapshot.Daily day = shownDays.get(i);
            views.addView(R.id.widget_days_rows, forecastRow(context,
                    Format.dayLabel(day.date, today), WeatherIcons.res(day.weatherCode, true),
                    Format.temperature(day.maxTemperature, units) + " / " + Format.temperature(day.minTemperature, units),
                    Format.precipitation(day.precipitation, units),
                    Format.percent(day.precipitationProbability),
                    i < shownDays.size() - 1));
        }
    }

    private static RemoteViews forecastRow(Context context, String label, int icon, String temperature,
                                           String rain, String probability, boolean showDivider) {
        RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_forecast_row);
        row.setTextViewText(R.id.widget_forecast_cell_label, label);
        row.setImageViewResource(R.id.widget_forecast_cell_icon, icon);
        row.setTextViewText(R.id.widget_forecast_cell_temp, temperature);
        row.setTextViewText(R.id.widget_forecast_cell_rain, rain);
        row.setTextViewText(R.id.widget_forecast_cell_probability, probability);
        row.setViewVisibility(R.id.widget_forecast_row_divider, showDivider ? View.VISIBLE : View.GONE);
        return row;
    }

    /** Horas de hoje ainda não passadas, com algum dado. Devolve índices em snapshot.hourly. */
    private static List<Integer> availableHourIndices(WeatherSnapshot snapshot) {
        List<Integer> out = new ArrayList<>();
        List<Integer> upcoming = ForecastTime.remainingToday(snapshot.hourlyTimes(), snapshot.localNow(), false);
        for (int index : upcoming) {
            WeatherSnapshot.Hourly hour = snapshot.hourly.get(index);
            if (hasValue(hour.temperature) || hasValue(hour.precipitation) || hasValue(hour.precipitationProbability)) {
                out.add(index);
            }
        }
        return out;
    }

    /** Dias depois de hoje, com algum dado. */
    private static List<WeatherSnapshot.Daily> availableDays(WeatherSnapshot snapshot, String today) {
        List<WeatherSnapshot.Daily> out = new ArrayList<>();
        for (WeatherSnapshot.Daily day : snapshot.upcomingDaily()) {
            if (day.date.compareTo(today) <= 0) continue;
            if (hasValue(day.maxTemperature) || hasValue(day.minTemperature)
                    || hasValue(day.precipitation) || hasValue(day.precipitationProbability)) {
                out.add(day);
            }
        }
        return out;
    }

    private static boolean hasValue(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static PendingIntent openIntent(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_FROM_WIDGET, true);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, OPEN_REQUEST, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent refreshIntent(Context context, int widgetId) {
        Intent intent = new Intent(context, WeatherWidgetProvider.class);
        intent.setAction(WeatherWidgetProvider.ACTION_REFRESH);
        return PendingIntent.getBroadcast(context, REFRESH_REQUEST_BASE + widgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---------- agendamento ----------

    /** Agenda o tick de hora em hora: acorda o provider sem rede e sem acordar o aparelho. */
    static void scheduleTick(Context context) {
        try {
            AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarms == null) return;
            Calendar next = Calendar.getInstance();
            next.add(Calendar.HOUR_OF_DAY, 1);
            next.set(Calendar.MINUTE, 0);
            next.set(Calendar.SECOND, 0);
            next.set(Calendar.MILLISECOND, 0);
            alarms.set(AlarmManager.RTC, next.getTimeInMillis() + 60_000L, tickIntent(context));
        } catch (RuntimeException unavailable) {
            // Sem alarme o widget só atualiza quando o usuário abre o app ou toca em atualizar.
        }
    }

    static void schedulePeriodic(Context context) {
        try {
            JobScheduler scheduler = jobScheduler(context);
            if (scheduler == null || scheduler.getPendingJob(JOB_PERIODIC) != null) return;
            JobInfo.Builder builder = new JobInfo.Builder(JOB_PERIODIC, jobService(context))
                    .setPeriodic(60 * 60_000L, 20 * 60_000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setRequiresBatteryNotLow(true);
            scheduler.schedule(builder.build());
        } catch (RuntimeException unavailable) {
            // O JobScheduler pode recusar o job (por exemplo, sem permissão de boot); o app segue funcionando.
        }
    }

    /** Pede uma atualização única assim que houver rede (substitui um pedido anterior). */
    static void requestRefreshWhenOnline(Context context) {
        try {
            JobScheduler scheduler = jobScheduler(context);
            if (scheduler == null) return;
            scheduler.schedule(new JobInfo.Builder(JOB_CATCH_UP, jobService(context))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build());
        } catch (RuntimeException unavailable) {
            // Ver schedulePeriodic.
        }
    }

    static void cancelAll(Context context) {
        try {
            JobScheduler scheduler = jobScheduler(context);
            if (scheduler != null) {
                scheduler.cancel(JOB_PERIODIC);
                scheduler.cancel(JOB_CATCH_UP);
            }
            AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarms != null) alarms.cancel(tickIntent(context));
        } catch (RuntimeException unavailable) {
            // Ver schedulePeriodic.
        }
    }

    /** Pede atualização se o lugar do widget tiver dados velhos. Sem lugar, não faz nada. */
    static void requestIfStale(Context context) {
        Place place = AppPrefs.widgetPlaceResolved(context);
        if (place == null) return;
        if (needsRefresh(WeatherCache.read(context, place), System.currentTimeMillis())) {
            requestRefreshWhenOnline(context);
        }
    }

    static boolean needsRefresh(WeatherSnapshot snapshot, long now) {
        return snapshot == null || snapshot.fetchedAt == 0 || now - snapshot.fetchedAt > STALE_MILLIS;
    }

    private static JobScheduler jobScheduler(Context context) {
        return (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    private static ComponentName jobService(Context context) {
        return new ComponentName(context, WeatherUpdateJobService.class);
    }

    private static PendingIntent tickIntent(Context context) {
        Intent intent = new Intent(context, WeatherWidgetProvider.class);
        intent.setAction(WeatherWidgetProvider.ACTION_TICK);
        return PendingIntent.getBroadcast(context, TICK_REQUEST, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---------- datas ----------

    private static String deviceToday() {
        return isoDay(System.currentTimeMillis());
    }

    private static String isoDay(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(millis));
    }

    private static String dayMonthClock(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        return String.format(Locale.US, "%02d/%02d %s", calendar.get(Calendar.DAY_OF_MONTH),
                calendar.get(Calendar.MONTH) + 1, Format.clockOf(millis));
    }
}
